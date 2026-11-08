package edu.hust.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PortableDatasetTest {
    @TempDir Path temp;

    @Test void copiedStorageUsesItsOwnRawFilesEvenWhileProducerStillExists() throws Exception {
        Path producer = temp.resolve("producer"), clone = temp.resolve("clone");
        List<Fixture> fixtures = createDataset(producer);
        copyDataset(producer, clone);
        for (Fixture fixture : fixtures) Files.writeString(producer.resolve(fixture.relative()), "producer changed after copy");

        try (var state = new ChangeDetectionService(clone.resolve("crawler.db"))) {
            var pages = state.storedPages();
            assertEquals(fixtures.size(), pages.size());
            for (Fixture fixture : fixtures) {
                var page = pages.stream().filter(value -> value.url().equals(fixture.url())).findFirst().orElseThrow();
                assertEquals(clone.resolve(fixture.relative()).toAbsolutePath(), page.path());
                assertEquals(page.contentHash(), ChangeDetectionService.computeSHA256(Files.readAllBytes(page.path())));
                var cached = state.cachedPage(fixture.url()).orElseThrow();
                assertEquals(page.path(), cached.path());
                assertEquals("\"fixture-etag\"", cached.etag());
                assertEquals("Wed, 04 Nov 2026 01:00:00 GMT", cached.lastModified());
            }
            Path manifest = clone.resolve("manifest.csv");
            state.exportManifest(manifest);
            String csv = Files.readString(manifest);
            assertFalse(csv.contains(producer.toAbsolutePath().toString()));
            for (Fixture fixture : fixtures) assertTrue(csv.contains(clone.resolve(fixture.relative()).toAbsolutePath().toString()));
        }
        // Reading/exporting a copied dataset does not rewrite its producer metadata.
        assertEquals(producer.resolve(fixtures.get(0).relative()).toAbsolutePath().toString(),
                persistedPath(clone.resolve("crawler.db"), fixtures.get(0).url()));
    }

    @Test void copiedDatasetCanBeExportedAfterOriginalFilesAreRemoved() throws Exception {
        Path producer = temp.resolve("old-machine"), clone = temp.resolve("new-machine");
        List<Fixture> fixtures = createDataset(producer);
        copyDataset(producer, clone);
        for (Fixture fixture : fixtures) Files.delete(producer.resolve(fixture.relative()));
        Files.delete(producer.resolve("crawler.db"));

        try (var state = new ChangeDetectionService(clone.resolve("crawler.db"))) {
            var summary = CrawlDatasetExporter.export(state, clone);
            assertEquals(2, summary.totalUrls());
            assertEquals(1, summary.htmlPages());
            assertEquals(1, summary.files());
            assertEquals(0, summary.unavailable());
            String urls = Files.readString(clone.resolve("crawl_urls.csv"));
            assertFalse(urls.contains(producer.toAbsolutePath().toString()));
            assertTrue(urls.contains(clone.resolve("raw_html/article.html").toAbsolutePath().toString()));
            assertTrue(urls.contains(clone.resolve("raw_files/document.pdf").toAbsolutePath().toString()));
        }
    }

    @Test void windowsProducerAndRelativeRawPathsAreResolvedWithoutPlatformSpecificParsing() throws Exception {
        Path producer = temp.resolve("windows-producer"), clone = temp.resolve("portable-clone");
        List<Fixture> fixtures = createDataset(producer);
        setPersistedPath(producer.resolve("crawler.db"), fixtures.get(0).url(),
                "Z:\\former-user\\project\\storage\\raw_html\\article.html");
        setPersistedPath(producer.resolve("crawler.db"), fixtures.get(1).url(), "raw_files\\document.pdf");
        setPersistedPath(producer.resolve("crawler.db"), fixtures.get(2).url(), "raw_feeds/feed.xml");
        copyDataset(producer, clone);

        try (var state = new ChangeDetectionService(clone.resolve("crawler.db"))) {
            for (Fixture fixture : fixtures) {
                Path actual = state.cachedPage(fixture.url()).orElseThrow().path();
                assertEquals(clone.resolve(fixture.relative()).toAbsolutePath(), actual);
                assertEquals(ChangeDetectionService.computeSHA256(fixture.bytes()),
                        ChangeDetectionService.computeSHA256(Files.readAllBytes(actual)));
            }
        }
    }

    @Test void arbitraryBasenamesAreNotRemappedToUnrelatedCloneFiles() throws Exception {
        Path original = temp.resolve("outside/a.html"), clone = temp.resolve("legacy-clone");
        Files.createDirectories(original.getParent()); Files.createDirectories(clone);
        Files.writeString(original, "original"); Files.writeString(clone.resolve("a.html"), "unrelated");
        String url = "https://hust.edu.vn/legacy";
        try (var state = new ChangeDetectionService(clone.resolve("crawler.db"))) {
            state.record(url, Files.readAllBytes(original), original, "text/html", "Legacy");
            assertEquals(original.toAbsolutePath(), state.storedPages().get(0).path());
            assertEquals(original.toAbsolutePath(), state.cachedPage(url).orElseThrow().path());
        }
    }

    private record Fixture(String url, String relative, String type, byte[] bytes) {}

    private List<Fixture> createDataset(Path storage) throws Exception {
        List<Fixture> fixtures = List.of(
                new Fixture("https://hust.edu.vn/article.html", "raw_html/article.html", "text/html",
                        "<title>Portable article</title><p>Original raw bytes</p>".getBytes(StandardCharsets.UTF_8)),
                new Fixture("https://hust.edu.vn/document.pdf", "raw_files/document.pdf", "application/pdf",
                        new byte[]{'%', 'P', 'D', 'F', '\r', '\n', 0, (byte) 0xff}),
                new Fixture("https://hust.edu.vn/feed", "raw_feeds/feed.xml", "application/rss+xml",
                        "<rss><channel/></rss>".getBytes(StandardCharsets.UTF_8)));
        try (var state = new ChangeDetectionService(storage.resolve("crawler.db"))) {
            for (Fixture fixture : fixtures) {
                Path raw = storage.resolve(fixture.relative()); Files.createDirectories(raw.getParent());
                Files.write(raw, fixture.bytes());
                state.record(fixture.url(), fixture.bytes(), raw, fixture.type(), "Fixture");
                state.recordHttp(fixture.url(), "\"fixture-etag\"", "Wed, 04 Nov 2026 01:00:00 GMT", 200);
            }
        }
        return fixtures;
    }

    private void copyDataset(Path source, Path destination) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path copy = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(copy);
                else Files.copy(path, copy);
            }
        }
    }

    private String persistedPath(Path database, String url) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var query = connection.prepareStatement("SELECT local_path FROM crawled_pages WHERE url=?")) {
            query.setString(1, url);
            try (var result = query.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }

    private void setPersistedPath(Path database, String url, String path) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var update = connection.prepareStatement("UPDATE crawled_pages SET local_path=? WHERE url=?")) {
            update.setString(1, path); update.setString(2, url); assertEquals(1, update.executeUpdate());
        }
    }
}
