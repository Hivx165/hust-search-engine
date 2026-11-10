package edu.hust.crawler;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.io.*;

/** A record is committed only after its raw file has been saved successfully. */
public final class ChangeDetectionService implements AutoCloseable {
    public enum Status { NEW, UPDATED, UNCHANGED }
    private final Connection connection;

    public ChangeDetectionService(Path database) throws Exception {
        Files.createDirectories(database.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS crawled_pages (url TEXT PRIMARY KEY, content_hash TEXT NOT NULL, last_scraped_at TEXT NOT NULL, status TEXT NOT NULL, local_path TEXT NOT NULL, content_type TEXT NOT NULL, title TEXT NOT NULL, subdomain TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS crawl_http_state (url TEXT PRIMARY KEY, etag TEXT, last_modified TEXT, http_status INTEGER NOT NULL, checked_at TEXT NOT NULL)");
        }
    }

    public static String computeSHA256(String content) { return computeSHA256(content.getBytes(StandardCharsets.UTF_8)); }
    public static String computeSHA256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public synchronized Status record(String url, byte[] bytes, Path file, String type, String title) throws SQLException {
        String hash = computeSHA256(bytes);
        Status status = Status.NEW;
        try (PreparedStatement q = connection.prepareStatement("SELECT content_hash FROM crawled_pages WHERE url=?")) {
            q.setString(1, url);
            try (ResultSet r = q.executeQuery()) { if (r.next()) status = hash.equals(r.getString(1)) ? Status.UNCHANGED : Status.UPDATED; }
        }
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO crawled_pages VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(url) DO UPDATE SET content_hash=excluded.content_hash,last_scraped_at=excluded.last_scraped_at,status=excluded.status,local_path=excluded.local_path,content_type=excluded.content_type,title=excluded.title,subdomain=excluded.subdomain")) {
            s.setString(1, url); s.setString(2, hash); s.setString(3, Instant.now().toString()); s.setString(4, status.name());
            s.setString(5, file.toAbsolutePath().toString()); s.setString(6, type); s.setString(7, title);
            s.setString(8, java.net.URI.create(url).getHost()); s.executeUpdate();
        }
        return status;
    }

    public synchronized long count() throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM crawled_pages")) { return r.getLong(1); }
    }

    /** A consistent metadata snapshot; raw bytes remain in the crawler's existing files. */
    public record StoredPage(String url, Path path, String contentType, String title,
                             String contentHash, String crawledAt, String status) {}

    public synchronized List<StoredPage> storedPages() throws SQLException {
        List<StoredPage> result = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(
                "SELECT url,local_path,content_type,title,content_hash,last_scraped_at,status FROM crawled_pages ORDER BY url")) {
            while (r.next()) result.add(new StoredPage(r.getString(1), Path.of(r.getString(2)),
                    r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7)));
        }
        return List.copyOf(result);
    }

    public synchronized List<URI> knownUrls() throws SQLException {
        List<URI> result = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT url FROM crawled_pages")) {
            while (r.next()) SubdomainDiscoverer.normalize(r.getString(1)).ifPresent(result::add);
        }
        return result;
    }

    public record CachedPage(Path path, String contentType, String etag, String lastModified) {}
    public synchronized Optional<CachedPage> cachedPage(String url) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("SELECT p.local_path,p.content_type,h.etag,h.last_modified FROM crawled_pages p LEFT JOIN crawl_http_state h ON h.url=p.url WHERE p.url=?")) {
            s.setString(1, url);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new CachedPage(Path.of(r.getString(1)), r.getString(2), r.getString(3), r.getString(4))) : Optional.empty();
            }
        }
    }

    public synchronized void recordHttp(String url, String etag, String lastModified, int status) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO crawl_http_state VALUES (?,?,?,?,?) ON CONFLICT(url) DO UPDATE SET etag=CASE WHEN excluded.http_status=304 THEN coalesce(excluded.etag,crawl_http_state.etag) ELSE excluded.etag END,last_modified=CASE WHEN excluded.http_status=304 THEN coalesce(excluded.last_modified,crawl_http_state.last_modified) ELSE excluded.last_modified END,http_status=excluded.http_status,checked_at=excluded.checked_at")) {
            s.setString(1, url); s.setString(2, etag); s.setString(3, lastModified); s.setInt(4, status);
            s.setString(5, Instant.now().toString()); s.executeUpdate();
        }
    }

    public synchronized void exportManifest(Path destination) throws SQLException, IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), "manifest-", ".tmp");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
                 Statement s = connection.createStatement();
                 ResultSet r = s.executeQuery("SELECT url,title,subdomain,content_type,last_scraped_at,content_hash,local_path,status FROM crawled_pages ORDER BY url")) {
                out.write("id,url,title,subdomain,content_type,crawled_at,content_hash,local_path,status\n");
                while (r.next()) {
                    out.write(csv(computeSHA256(r.getString(1))));
                    for (int i = 1; i <= 8; i++) { out.write(','); out.write(csv(r.getString(i))); }
                    out.newLine();
                }
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    @Override public void close() throws SQLException { connection.close(); }
}
