package edu.hust.crawler;

import org.jsoup.Jsoup;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Offline exports. ARTICLE is the analyzer's heuristic label, not a manually judged relevance label. */
public final class CrawlDatasetExporter {
    public record Summary(int totalUrls, int htmlPages, int articles, int files, int observedLinks,
                          int unavailable, int analyzed, int cacheHits, String snapshotAt) {}
    private record Page(ChangeDetectionService.StoredPage stored, String type, String quality,
                        String reason, String title, int textLength, boolean available) {}
    private record Cached(String hash, String version, String kind, String reason, String title, int length) {}
    private static final String[] URL_HEADER = {"url", "subdomain", "file_type", "content_type", "title", "quality", "reason",
            "text_length", "raw_available", "local_path", "content_hash", "crawled_at", "status"};
    private CrawlDatasetExporter() {}

    public static Summary export(ChangeDetectionService state, Path storage) throws Exception {
        try {
            Summary summary = exportSnapshot(state, storage);
            status(storage, "success", summary.toString(), summary.snapshotAt());
            return summary;
        } catch (Exception e) {
            try { status(storage, "error", e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), ""), ""); }
            catch (IOException failure) { e.addSuppressed(failure); }
            throw e;
        }
    }

    private static Summary exportSnapshot(ChangeDetectionService state, Path storage) throws Exception {
        Files.createDirectories(storage);
        List<ChangeDetectionService.StoredPage> snapshot = state.storedPages();
        String at = Instant.now().toString(), version = classifierVersion();
        List<Page> pages = new ArrayList<>();
        int analyzed = 0, hits = 0;
        try (Connection database = DriverManager.getConnection("jdbc:sqlite:" + storage.resolve("dataset.db").toAbsolutePath())) {
            initialize(database);
            for (ChangeDetectionService.StoredPage stored : snapshot) {
                Optional<URI> normalized = SubdomainDiscoverer.normalize(stored.url());
                if (normalized.isEmpty()) continue;
                String type = type(stored);
                if (type.equals("FEED")) continue;
                boolean html = type.equals("HTML");
                if (html) {
                    try (PreparedStatement active = database.prepareStatement("INSERT OR IGNORE INTO active_sources(url) VALUES (?)")) {
                        active.setString(1, stored.url()); active.executeUpdate();
                    }
                }
                if (!Files.isRegularFile(stored.path())) {
                    pages.add(new Page(stored, type, "UNAVAILABLE", "RAW_FILE_MISSING", clean(stored.title()), 0, false));
                    continue;
                }
                if (!html) {
                    // A formerly HTML URL may now serve a binary attachment.
                    removeAnalysis(database, stored.url());
                    pages.add(new Page(stored, type, "FILE", "BINARY_ATTACHMENT", clean(stored.title()), 0, true));
                    continue;
                }
                try (InputStream input = Files.newInputStream(stored.path())) {
                    Cached cached = cached(database, stored.url());
                    if (cached != null && cached.hash().equals(stored.contentHash()) && cached.version().equals(version)) {
                        pages.add(new Page(stored, type, cached.kind(), cached.reason(), cached.title(), cached.length(), true)); hits++;
                    } else {
                        var document = Jsoup.parse(input, null, stored.url());
                        HtmlDatasetAnalyzer.Analysis analysis = HtmlDatasetAnalyzer.analyze(document, normalized.get());
                        replaceAnalysis(database, stored, analysis, version, at);
                        pages.add(new Page(stored, type, analysis.kind(), analysis.reason(), analysis.title(), analysis.textLength(), true)); analyzed++;
                    }
                } catch (IOException e) {
                    // Prior analysis/edges remain available for a later retry; a missing body cannot be an ARTICLE now.
                    pages.add(new Page(stored, type, "UNAVAILABLE", "RAW_FILE_UNREADABLE", clean(stored.title()), 0, false));
                }
            }
            pages.sort(Comparator.comparing(page -> page.stored().url()));
            writeCsv(storage.resolve("crawl_urls.csv"), out -> {
                row(out, URL_HEADER);
                for (Page page : pages) urlRow(out, page);
            });
            writeCsv(storage.resolve("articles.csv"), out -> {
                row(out, URL_HEADER);
                for (Page page : pages) if (page.available() && page.quality().equals("ARTICLE")) urlRow(out, page);
            });
            writeCsv(storage.resolve("page_quality.csv"), out -> {
                row(out, "url", "subdomain", "kind", "reason", "title", "text_length", "raw_available", "content_hash", "crawled_at");
                for (Page page : pages) if (page.type().equals("HTML")) row(out, page.stored().url(), host(page.stored().url()),
                        page.quality(), page.reason(), page.title(), Integer.toString(page.textLength()), Boolean.toString(page.available()),
                        page.stored().contentHash(), page.stored().crawledAt());
            });
            int[] links = {0};
            writeCsv(storage.resolve("links.csv"), out -> {
                row(out, "source_url", "target_url", "source_subdomain", "target_subdomain", "anchor_text", "relation");
                try (Statement statement = database.createStatement(); ResultSet result = statement.executeQuery(
                        "SELECT l.source_url,l.target_url,l.anchor_text,l.relation FROM page_links l "
                                + "JOIN active_sources a ON a.url=l.source_url ORDER BY l.source_url,l.target_url,l.relation")) {
                    while (result.next()) {
                        String source = result.getString(1), target = result.getString(2);
                        row(out, source, target, host(source), host(target), result.getString(3), result.getString(4)); links[0]++;
                    }
                }
            });
            Summary summary = new Summary(pages.size(), (int) pages.stream().filter(page -> page.type().equals("HTML")).count(),
                    (int) pages.stream().filter(page -> page.available() && page.quality().equals("ARTICLE")).count(),
                    (int) pages.stream().filter(page -> !page.type().equals("HTML")).count(), links[0],
                    (int) pages.stream().filter(page -> !page.available()).count(), analyzed, hits, at);
            writeAtomic(storage.resolve("dataset_summary.txt"), (summary + "\nclassifier_version=" + version
                    + "\nARTICLE is a heuristic classification. Observed targets need not have been fetched."
                    + "\nUNAVAILABLE raw bodies retain prior cached edges for retry; consult page_quality.csv.\n").getBytes(StandardCharsets.UTF_8));
            return summary;
        }
    }

    private static void initialize(Connection database) throws SQLException {
        try (Statement statement = database.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000"); statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE IF NOT EXISTS dataset_pages (url TEXT PRIMARY KEY,content_hash TEXT NOT NULL,"
                    + "classifier_version TEXT NOT NULL,kind TEXT NOT NULL,reason TEXT NOT NULL,title TEXT NOT NULL,"
                    + "text_length INTEGER NOT NULL,analyzed_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS page_links (source_url TEXT NOT NULL,target_url TEXT NOT NULL,"
                    + "relation TEXT NOT NULL,anchor_text TEXT NOT NULL,PRIMARY KEY(source_url,target_url,relation),"
                    + "FOREIGN KEY(source_url) REFERENCES dataset_pages(url) ON DELETE CASCADE)");
            statement.execute("CREATE TEMP TABLE active_sources (url TEXT PRIMARY KEY)");
        }
    }

    private static Cached cached(Connection database, String url) throws SQLException {
        try (PreparedStatement query = database.prepareStatement("SELECT content_hash,classifier_version,kind,reason,title,text_length FROM dataset_pages WHERE url=?")) {
            query.setString(1, url);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new Cached(result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                        result.getString(5), result.getInt(6)) : null;
            }
        }
    }

    private static void replaceAnalysis(Connection database, ChangeDetectionService.StoredPage stored,
                                        HtmlDatasetAnalyzer.Analysis analysis, String version, String at) throws SQLException {
        database.setAutoCommit(false);
        try {
            try (PreparedStatement update = database.prepareStatement("INSERT INTO dataset_pages VALUES (?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT(url) DO UPDATE SET content_hash=excluded.content_hash,classifier_version=excluded.classifier_version,"
                    + "kind=excluded.kind,reason=excluded.reason,title=excluded.title,text_length=excluded.text_length,analyzed_at=excluded.analyzed_at");
                 PreparedStatement clear = database.prepareStatement("DELETE FROM page_links WHERE source_url=?");
                 PreparedStatement insert = database.prepareStatement("INSERT OR IGNORE INTO page_links VALUES (?,?,?,?)")) {
                update.setString(1, stored.url()); update.setString(2, stored.contentHash()); update.setString(3, version);
                update.setString(4, analysis.kind()); update.setString(5, analysis.reason()); update.setString(6, analysis.title());
                update.setInt(7, analysis.textLength()); update.setString(8, at); update.executeUpdate();
                clear.setString(1, stored.url()); clear.executeUpdate();
                int batch = 0;
                for (HtmlDatasetAnalyzer.Link link : analysis.links()) {
                    Optional<URI> target = SubdomainDiscoverer.normalize(link.target().toString());
                    if (target.isEmpty()) continue;
                    insert.setString(1, stored.url()); insert.setString(2, target.get().toString());
                    insert.setString(3, link.relation()); insert.setString(4, link.anchorText()); insert.addBatch();
                    if (++batch % 1000 == 0) insert.executeBatch();
                }
                if (batch % 1000 != 0) insert.executeBatch();
            }
            database.commit();
        } catch (SQLException | RuntimeException e) { database.rollback(); throw e; }
        finally { database.setAutoCommit(true); }
    }

    private static void removeAnalysis(Connection database, String url) throws SQLException {
        try (PreparedStatement clear = database.prepareStatement("DELETE FROM dataset_pages WHERE url=?")) {
            clear.setString(1, url); clear.executeUpdate();
        }
    }
    private static String type(ChangeDetectionService.StoredPage page) {
        String mime = clean(page.contentType()).toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (Set.of("application/xml", "text/xml", "application/rss+xml", "application/atom+xml").contains(mime)) return "FEED";
        if (Set.of("text/html", "application/xhtml+xml").contains(mime)) return "HTML";
        String path = page.path().getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : List.of("pdf", "docx", "doc", "xlsx")) if (path.endsWith("." + extension)) return extension.toUpperCase(Locale.ROOT);
        return switch (mime) {
            case "application/pdf" -> "PDF";
            case "application/msword" -> "DOC";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX";
            default -> path.endsWith(".xml") || path.endsWith(".xml.gz") ? "FEED" : "FILE";
        };
    }
    private static String host(String url) { return URI.create(url).getHost(); }
    private static String clean(String value) { return Objects.toString(value, ""); }
    private static void status(Path storage, String result, String detail, String snapshotAt) throws IOException {
        Files.createDirectories(storage);
        Properties properties = new Properties();
        properties.setProperty("status", result); properties.setProperty("updated_at", Instant.now().toString());
        properties.setProperty("detail", detail);
        if (!snapshotAt.isEmpty()) properties.setProperty("snapshot_at", snapshotAt);
        StringWriter text = new StringWriter(); properties.store(text, "Dataset export status");
        writeAtomic(storage.resolve("dataset_export_status.properties"), text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String classifierVersion() {
        try (InputStream input = HtmlDatasetAnalyzer.class.getResourceAsStream("HtmlDatasetAnalyzer.class")) {
            return input == null ? "html-dataset-v1" : ChangeDetectionService.computeSHA256(input.readAllBytes());
        } catch (IOException e) { return "html-dataset-v1"; }
    }
    private static void urlRow(Writer out, Page page) throws IOException {
        var stored = page.stored();
        row(out, stored.url(), host(stored.url()), page.type(), stored.contentType(), page.title(), page.quality(), page.reason(),
                Integer.toString(page.textLength()), Boolean.toString(page.available()), stored.path().toString(),
                stored.contentHash(), stored.crawledAt(), stored.status());
    }
    private static void row(Writer out, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i != 0) out.write(',');
            String value = clean(cells[i]), leading = value.stripLeading();
            if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0
                    || !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            out.write('"'); out.write(value.replace("\"", "\"\"")); out.write('"');
        }
        out.write("\r\n");
    }
    @FunctionalInterface private interface CsvContent { void write(Writer out) throws Exception; }
    private static void writeCsv(Path destination, CsvContent content) throws Exception {
        Path temp = Files.createTempFile(destination.toAbsolutePath().getParent(), "dataset-", ".tmp");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { out.write('\uFEFF'); content.write(out); }
            move(temp, destination);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void writeAtomic(Path destination, byte[] content) throws IOException {
        Path temp = Files.createTempFile(destination.toAbsolutePath().getParent(), "dataset-", ".tmp");
        try { Files.write(temp, content); move(temp, destination); } finally { Files.deleteIfExists(temp); }
    }
    private static void move(Path temp, Path destination) throws IOException {
        try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
    }
}
