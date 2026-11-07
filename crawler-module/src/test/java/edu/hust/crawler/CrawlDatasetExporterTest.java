package edu.hust.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrawlDatasetExporterTest {
    @TempDir Path temp;

    @Test void exportsScopedEdgesUnicodeFormulaSafeCsvAndSeparatesArticlesFromFilesAndFeeds() throws Exception {
        String source = "https://soict.hust.edu.vn/tin-tuc/bai-viet-thu-nghiem.html";
        String target = "https://soict.hust.edu.vn/target.pdf?x=1&y=2";
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            register(state, source, "article.html", "text/html; charset=UTF-8", "=SUM(1,2)", article("=SUM(1,2)",
                    "<a href='/target.pdf?x=1&amp;y=2#one'>@Tài liệu &quot;HUST&quot;</a>"
                            + "<a href='/target.pdf?x=1&amp;y=2#two'>Trùng</a>"
                            + "<iframe src='https://other.hust.edu.vn/frame' title='Nội dung nhúng'></iframe>"
                            + "<a href='https://hust.edu.vn.evil.com/private'>Outside</a>"));
            String fileTitle = "\t=SUM(1,2)\r\nTài liệu \"HUST\"";
            register(state, "https://soict.hust.edu.vn/files/document.pdf", "document.pdf", "application/pdf", fileTitle, "%PDF-1.4\nfixture");
            register(state, "https://soict.hust.edu.vn/rss", "feed.xml", "application/rss+xml", "RSS", "<rss><channel/></rss>");
            var summary = CrawlDatasetExporter.export(state, temp);
            assertEquals(2, summary.totalUrls()); assertEquals(1, summary.htmlPages());
            assertEquals(1, summary.articles()); assertEquals(1, summary.files()); assertEquals(2, summary.observedLinks());
            assertEquals(1, summary.analyzed()); assertEquals(0, summary.cacheHits());
            assertEquals(1, csv(temp.resolve("articles.csv")).size());
            var urls = csv(temp.resolve("crawl_urls.csv"));
            assertEquals(Set.of("HTML", "PDF"), new HashSet<>(urls.stream().map(row -> row.get("file_type")).toList()));
            assertTrue(urls.stream().anyMatch(row -> row.get("title").equals("'" + fileTitle)));
            assertTrue(csv(temp.resolve("articles.csv")).get(0).get("title").startsWith("'=SUM"));
            var edges = csv(temp.resolve("links.csv"));
            assertTrue(edges.stream().anyMatch(row -> row.get("source_url").equals(source) && row.get("target_url").equals(target)
                    && row.get("anchor_text").equals("'@Tài liệu \"HUST\"")));
            assertTrue(edges.stream().anyMatch(row -> row.get("relation").equals("iframe")));
            assertFalse(edges.stream().anyMatch(row -> row.get("target_url").contains("evil.com")));
            assertTrue(Files.readString(temp.resolve("links.csv")).contains("\"" + source + "\",\"" + target + "\""));
            for (String name : List.of("crawl_urls.csv", "articles.csv", "page_quality.csv", "links.csv")) {
                byte[] bytes = Files.readAllBytes(temp.resolve(name));
                assertArrayEquals(new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF}, Arrays.copyOf(bytes, 3));
            }
            assertEquals("success", status().getProperty("status"));
        }
    }

    @Test void cacheInvalidatesForContentAndClassifierChangesAndPurgesOldEdges() throws Exception {
        String url = "https://soict.hust.edu.vn/bai-viet-thong-bao.html";
        Path raw;
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            raw = register(state, url, "page.html", "text/html", "Bài viết", article("Bài viết", "<a href='/old.pdf'>Old</a>"));
            assertEquals(1, CrawlDatasetExporter.export(state, temp).analyzed());
            var unchanged = CrawlDatasetExporter.export(state, temp);
            assertEquals(0, unchanged.analyzed()); assertEquals(1, unchanged.cacheHits());
            try (var db = dataset(); var update = db.createStatement()) {
                update.executeUpdate("UPDATE dataset_pages SET classifier_version='older-classifier'");
            }
            assertEquals(1, CrawlDatasetExporter.export(state, temp).analyzed());
            String suspended = "<title>Account Suspended</title><p>This account has been suspended</p><a href='/help'>Help</a>";
            Files.writeString(raw, suspended, StandardCharsets.UTF_8);
            state.record(url, suspended.getBytes(StandardCharsets.UTF_8), raw, "text/html", "Account Suspended");
            var changed = CrawlDatasetExporter.export(state, temp);
            assertEquals(1, changed.analyzed()); assertEquals(0, changed.articles());
            var audit = csv(temp.resolve("page_quality.csv")).get(0);
            assertEquals("PLACEHOLDER", audit.get("kind")); assertEquals("SUSPENDED_ACCOUNT", audit.get("reason"));
            var links = csv(temp.resolve("links.csv"));
            assertEquals(1, links.size()); assertEquals("https://soict.hust.edu.vn/help", links.get(0).get("target_url"));
            try (var db = dataset(); var query = db.createStatement(); var result = query.executeQuery("SELECT count(*) FROM page_links WHERE target_url LIKE '%old.pdf'")) {
                assertTrue(result.next()); assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test void missingBodyIsAuditedAndExcludedFromArticlesWithoutErasingPriorEdges() throws Exception {
        String url = "https://soict.hust.edu.vn/bai-viet-can-cap-nhat.html";
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            Path raw = register(state, url, "body.html", "text/html", "Thông báo", article("Thông báo", "<a href='/before'>Before</a>"));
            assertEquals(1, CrawlDatasetExporter.export(state, temp).articles());
            String next = article("Thông báo mới", "<a href='/after'>After</a>");
            Files.delete(raw);
            state.record(url, next.getBytes(StandardCharsets.UTF_8), raw, "text/html", "Thông báo mới");
            var missing = CrawlDatasetExporter.export(state, temp);
            assertEquals(1, missing.unavailable()); assertEquals(0, missing.articles());
            assertEquals(1, missing.observedLinks());
            assertEquals("UNAVAILABLE", csv(temp.resolve("page_quality.csv")).get(0).get("kind"));
            assertEquals("RAW_FILE_MISSING", csv(temp.resolve("page_quality.csv")).get(0).get("reason"));
            assertEquals("https://soict.hust.edu.vn/before", csv(temp.resolve("links.csv")).get(0).get("target_url"));
            Files.writeString(raw, next, StandardCharsets.UTF_8);
            var recovered = CrawlDatasetExporter.export(state, temp);
            assertEquals(1, recovered.analyzed()); assertEquals(1, recovered.articles());
            assertEquals("https://soict.hust.edu.vn/after", csv(temp.resolve("links.csv")).get(0).get("target_url"));
        }
    }

    @Test void urlPathCaseRemainsDistinctAndRemovedSourcesCannotLeakIntoCurrentLinkSnapshot() throws Exception {
        Path statePath = temp.resolve("crawler.db");
        String upper = "https://soict.hust.edu.vn/Detail-Notice.html", lower = "https://soict.hust.edu.vn/detail-notice.html";
        try (var state = new ChangeDetectionService(statePath)) {
            register(state, upper, "upper.html", "text/html", "Upper", article("Upper", "<a href='/target'>Target</a>"));
            register(state, lower, "lower.html", "text/html", "Lower", article("Lower", "<a href='/target'>Target</a>"));
            var both = CrawlDatasetExporter.export(state, temp);
            assertEquals(2, both.totalUrls()); assertEquals(2, both.observedLinks());
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + statePath.toAbsolutePath());
                 var delete = db.prepareStatement("DELETE FROM crawled_pages WHERE url=?")) {
                delete.setString(1, upper); assertEquals(1, delete.executeUpdate());
            }
            var current = CrawlDatasetExporter.export(state, temp);
            assertEquals(1, current.totalUrls()); assertEquals(1, current.observedLinks());
            assertEquals(lower, csv(temp.resolve("links.csv")).get(0).get("source_url"));
        }
    }

    @Test void exporterErrorsAreVisibleAndDoNotDestroyEarlierCsvSnapshots() throws Exception {
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            register(state, "https://soict.hust.edu.vn/bai-viet-xuat-bao-cao.html", "raw.html", "text/html", "Báo cáo", article("Báo cáo", ""));
            CrawlDatasetExporter.export(state, temp);
            byte[] before = Files.readAllBytes(temp.resolve("articles.csv"));
            Files.writeString(temp.resolve("dataset.db"), "invalid SQLite contents", StandardCharsets.UTF_8);
            assertThrows(Exception.class, () -> CrawlDatasetExporter.export(state, temp));
            assertEquals("error", status().getProperty("status"));
            assertFalse(status().getProperty("detail", "").isBlank());
            assertArrayEquals(before, Files.readAllBytes(temp.resolve("articles.csv")));
        }
    }

    private Properties status() throws IOException {
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(temp.resolve("dataset_export_status.properties"), StandardCharsets.UTF_8)) { values.load(reader); }
        return values;
    }
    private Connection dataset() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("dataset.db").toAbsolutePath()); }
    private Path register(ChangeDetectionService state, String url, String filename, String mime, String title, String body) throws Exception {
        Path raw = temp.resolve(filename); Files.writeString(raw, body, StandardCharsets.UTF_8);
        state.record(url, body.getBytes(StandardCharsets.UTF_8), raw, mime, title); return raw;
    }
    private static String article(String title, String links) {
        return "<meta property='og:type' content='article'><title>" + title + "</title><article><h1>" + title + "</h1><p>"
                + "Nội dung thông báo cho sinh viên Bách khoa Hà Nội. ".repeat(30) + "</p>" + links + "</article>";
    }
    /** Independent CSV reader for quoted multiline records and embedded quote assertions. */
    private static List<Map<String, String>> csv(Path path) throws IOException {
        String value = Files.readString(path, StandardCharsets.UTF_8);
        if (value.startsWith("\uFEFF")) value = value.substring(1);
        List<List<String>> records = new ArrayList<>(); List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (!quoted && character == ',') { cells.add(cell.toString()); cell.setLength(0); }
            else if (!quoted && (character == '\r' || character == '\n')) {
                if (character == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
                cells.add(cell.toString()); cell.setLength(0); records.add(cells); cells = new ArrayList<>();
            } else cell.append(character);
        }
        assertFalse(quoted, "CSV quote must close");
        List<String> header = records.remove(0); List<Map<String, String>> result = new ArrayList<>();
        for (List<String> record : records) {
            assertEquals(header.size(), record.size(), "CSV column count");
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), record.get(i));
            result.add(row);
        }
        return result;
    }
}
