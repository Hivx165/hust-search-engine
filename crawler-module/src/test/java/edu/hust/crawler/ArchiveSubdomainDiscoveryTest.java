package edu.hust.crawler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveSubdomainDiscoveryTest {
    @TempDir Path temp;

    @Test void collectsScopedHostsFromWaybackFooterAndCommonCrawlJsonlPages() throws Exception {
        AtomicInteger wbPages = new AtomicInteger(), ccPages = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> {
            String query = decodedQuery(exchange);
            assertTrue(query.contains("url=hust.edu.vn/"));
            assertTrue(query.contains("fl=original")); assertTrue(query.contains("collapse=urlkey"));
            assertTrue(query.contains("limit=5000")); assertTrue(query.contains("showResumeKey=true"));
            if (wbPages.incrementAndGet() == 1) respond(exchange, 200, """
                    [["original"],["https://ONE.hust.edu.vn/article"],["https://hust.edu.vn/"],
                     ["https://hust.edu.vn.evil.com/"],["https://user@bad.hust.edu.vn/"],["https://*.hust.edu.vn/"],[],["opaque+key 1"]]
                    """);
            else {
                assertTrue(query.contains("resumeKey=opaque+key 1"));
                respond(exchange, 200, "[[\"original\"],[\"http://deep.one.hust.edu.vn/news\"]]");
            }
        });
        catalog(server, "/index");
        server.createContext("/index", exchange -> {
            String query = decodedQuery(exchange);
            assertTrue(query.contains("url=hust.edu.vn/&matchType=domain")); assertFalse(query.contains("*"));
            assertTrue(query.contains("pageSize=1"));
            assertTrue(query.contains("fl=url")); assertFalse(query.contains("collapse=host"));
            if (query.contains("showNumPages=true")) respond(exchange, 200, "{\"pages\":2,\"pageSize\":1,\"blocks\":2}");
            else {
                ccPages.incrementAndGet();
                if (query.contains("page=0")) respond(exchange, 200, "{\"url\":\"https://one.hust.edu.vn/a\"}\n{\"url\":\"http://cc.hust.edu.vn/b\"}\n{\"url\":\"https://outside.com/\"}\n");
                else respond(exchange, 200, "{\"url\":\"https://last.hust.edu.vn/\"}\n");
            }
        });
        server.start();
        try {
            var result = source(server, 3, new MutableClock()).discover(temp);
            assertTrue(result.complete()); assertTrue(result.errors().isEmpty());
            assertEquals(Set.of("one.hust.edu.vn", "deep.one.hust.edu.vn", "cc.hust.edu.vn", "last.hust.edu.vn"), result.hosts());
            assertEquals(Set.of("one.hust.edu.vn", "deep.one.hust.edu.vn"), result.bySource().get("wayback"));
            assertEquals(2, wbPages.get()); assertEquals(2, ccPages.get());
            assertThrows(UnsupportedOperationException.class, () -> result.hosts().add("bad.hust.edu.vn"));
            assertTrue(Files.readString(temp.resolve("passive-discovery/archive-source-status.txt")).contains("complete=true"));
        } finally { server.stop(0); }
    }

    @Test void pageBudgetsPersistCursorsAcrossRestartsAndCompletedResultsUseTheCache() throws Exception {
        AtomicInteger wbCalls = new AtomicInteger(), catalogCalls = new AtomicInteger(), metadataCalls = new AtomicInteger();
        List<String> ccQueries = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> {
            wbCalls.incrementAndGet();
            if (decodedQuery(exchange).contains("resumeKey=next")) respond(exchange, 200, "[[\"original\"],[\"https://wb-two.hust.edu.vn/\"]]");
            else respond(exchange, 200, "[[\"original\"],[\"https://wb-one.hust.edu.vn/\"],[],[\"next\"]]");
        });
        server.createContext("/collections", exchange -> {
            catalogCalls.incrementAndGet(); respond(exchange, 200, catalogJson(server, "/index"));
        });
        server.createContext("/index", exchange -> {
            String query = decodedQuery(exchange); ccQueries.add(query);
            if (query.contains("showNumPages=true")) { metadataCalls.incrementAndGet(); respond(exchange, 200, "{\"pages\":2}"); }
            else respond(exchange, 200, "{\"url\":\"https://cc-" + (query.contains("page=0") ? "one" : "two") + ".hust.edu.vn/\"}\n");
        });
        server.start();
        MutableClock clock = new MutableClock();
        try {
            var first = source(server, 1, clock).discover(temp);
            assertFalse(first.complete()); assertEquals(2, first.errors().size());
            assertEquals(Set.of("wb-one.hust.edu.vn", "cc-one.hust.edu.vn"), first.hosts());
            var cachedPartial = source(server, 1, clock).discover(temp);
            assertFalse(cachedPartial.complete()); assertEquals(1, wbCalls.get()); assertEquals(1, catalogCalls.get());
            clock.advance(Duration.ofMinutes(61));
            var finished = source(server, 1, clock).discover(temp);
            assertTrue(finished.complete()); assertEquals(4, finished.hosts().size());
            assertEquals(2, wbCalls.get()); assertEquals(2, catalogCalls.get()); assertEquals(1, metadataCalls.get());
            assertEquals(1, ccQueries.stream().filter(query -> query.contains("page=0")).count());
            assertEquals(1, ccQueries.stream().filter(query -> query.contains("page=1")).count());
            var cachedComplete = source(server, 1, clock).discover(temp);
            assertTrue(cachedComplete.complete()); assertEquals(2, wbCalls.get()); assertEquals(2, catalogCalls.get());
            assertTrue(Files.readString(temp.resolve("passive-discovery/wayback-state.json")).contains("wb-one.hust.edu.vn"));
        } finally { server.stop(0); }
    }

    @Test void newerCommonCrawlCatalogResetsPageCursorWhileKeepingHistoricalHostnames() throws Exception {
        AtomicInteger version = new AtomicInteger(), newPages = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> respond(exchange, 200, "[]"));
        server.createContext("/collections", exchange -> respond(exchange, 200, catalogJson(server, version.get() == 0 ? "/old-index" : "/new-index")));
        server.createContext("/old-index", exchange -> {
            if (decodedQuery(exchange).contains("showNumPages=true")) respond(exchange, 200, "{\"pages\":2}");
            else respond(exchange, 200, "{\"url\":\"https://old.hust.edu.vn/\"}\n");
        });
        server.createContext("/new-index", exchange -> {
            if (decodedQuery(exchange).contains("showNumPages=true")) respond(exchange, 200, "{\"pages\":1}");
            else { newPages.incrementAndGet(); assertTrue(decodedQuery(exchange).contains("page=0")); respond(exchange, 200, "{\"url\":\"https://new.hust.edu.vn/\"}\n"); }
        });
        server.start(); MutableClock clock = new MutableClock();
        try {
            assertFalse(source(server, 1, clock).discover(temp).complete());
            version.incrementAndGet(); clock.advance(Duration.ofHours(2));
            var latest = source(server, 1, clock).discover(temp);
            assertTrue(latest.complete()); assertEquals(Set.of("old.hust.edu.vn", "new.hust.edu.vn"), latest.hosts());
            assertEquals(1, newPages.get());
        } finally { server.stop(0); }
    }

    @Test void completedCycleRestartsAfterTheConfiguredRefreshInterval() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> {
            calls.incrementAndGet(); assertFalse(decodedQuery(exchange).contains("resumeKey="));
            respond(exchange, 200, "[[\"original\"],[\"https://ttl.hust.edu.vn/\"]]");
        });
        emptyCommonCrawl(server);
        server.start(); MutableClock clock = new MutableClock();
        try {
            assertTrue(source(server, 3, clock).discover(temp).complete());
            clock.advance(Duration.ofHours(23)); assertTrue(source(server, 3, clock).discover(temp).complete());
            assertEquals(1, calls.get());
            clock.advance(Duration.ofHours(2)); assertTrue(source(server, 3, clock).discover(temp).complete());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test void rateLimitRetainsSuccessfulPagesAndResumesTheirCursorAfterRetryAfter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) respond(exchange, 200, "[[\"original\"],[\"https://first.hust.edu.vn/\"],[],[\"resume\"]]");
            else if (call == 2) {
                exchange.getResponseHeaders().add("Retry-After", "3600"); respond(exchange, 429, "rate limited");
            } else {
                assertTrue(decodedQuery(exchange).contains("resumeKey=resume"));
                respond(exchange, 200, "[[\"original\"],[\"https://after.hust.edu.vn/\"]]");
            }
        });
        emptyCommonCrawl(server); server.start(); MutableClock clock = new MutableClock();
        try {
            var limited = source(server, 3, clock).discover(temp);
            assertFalse(limited.complete()); assertEquals(Set.of("first.hust.edu.vn"), limited.hosts());
            assertTrue(limited.errors().get(0).contains("Retry-After=3600"));
            clock.advance(Duration.ofMinutes(31)); source(server, 3, clock).discover(temp); assertEquals(2, calls.get());
            clock.advance(Duration.ofMinutes(31)); var resumed = source(server, 3, clock).discover(temp);
            assertTrue(resumed.complete()); assertEquals(Set.of("first.hust.edu.vn", "after.hust.edu.vn"), resumed.hosts());
            assertEquals(3, calls.get());
            try (var files = Files.walk(temp.resolve("passive-discovery/history"))) {
                assertTrue(files.filter(Files::isRegularFile).anyMatch(path -> {
                    try { return Files.readString(path).equals("rate limited"); } catch (IOException e) { return false; }
                }));
            }
        } finally { server.stop(0); }
    }

    @Test void invalidSourceSchemasAndUntrustedCatalogEndpointsAreErrorsRatherThanCompleteEmptyResults() throws Exception {
        HttpServer server = server();
        server.createContext("/wayback", exchange -> respond(exchange, 200, "{\"error\":\"not an array\"}"));
        server.createContext("/collections", exchange -> respond(exchange, 200, """
                [{"cdx-api":"https://index.commoncrawl.org.evil.com/index"},
                 {"cdx-api":"https://user@index.commoncrawl.org/index"},
                 {"cdx-api":"http://index.commoncrawl.org/index"}]
                """));
        server.start();
        try {
            var result = source(server, 3, new MutableClock()).discover(temp);
            assertFalse(result.complete()); assertTrue(result.hosts().isEmpty()); assertEquals(2, result.errors().size());
            assertTrue(result.errors().stream().anyMatch(error -> error.contains("No valid Common Crawl")));
        } finally { server.stop(0); }
    }

    @Test void missingPageMetadataAndMalformedJsonlDoNotAdvanceTheCommonCrawlCursor() throws Exception {
        AtomicInteger version = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> respond(exchange, 200, "[]"));
        catalog(server, "/index");
        server.createContext("/index", exchange -> {
            if (decodedQuery(exchange).contains("showNumPages=true")) respond(exchange, 200, version.get() == 0 ? "{}" : "{\"pages\":1}");
            else respond(exchange, 200, "{\"url\":\"https://valid.hust.edu.vn/\"}\n{\"wrong\":true}\n");
        });
        server.start(); MutableClock clock = new MutableClock();
        try {
            var metadata = source(server, 3, clock).discover(temp);
            assertFalse(metadata.complete()); assertTrue(metadata.errors().get(0).contains("metadata"));
            version.incrementAndGet(); clock.advance(Duration.ofMinutes(31));
            var jsonl = source(server, 3, clock).discover(temp);
            assertFalse(jsonl.complete()); assertTrue(jsonl.errors().get(0).contains("JSONL"));
            assertTrue(jsonl.hosts().isEmpty());
            assertTrue(Files.readString(temp.resolve("passive-discovery/commoncrawl-state.json")).contains("\"nextPage\":0"));
        } finally { server.stop(0); }
    }

    @Test void supportsWaybackPlainTextResumeFooterAndDetectsRepeatedKeys() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "[[\"original\"],[\"https://plain.hust.edu.vn/\"]]\n\nopaque-key");
        });
        emptyCommonCrawl(server); server.start();
        try {
            var result = source(server, 3, new MutableClock()).discover(temp);
            assertFalse(result.complete()); assertEquals(Set.of("plain.hust.edu.vn"), result.hosts());
            assertTrue(result.errors().get(0).contains("Repeated Wayback resume key")); assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test void oldZeroMetadataIsRefreshedOnceForTheCorrectedQueryThenUsesTheNewCache() throws Exception {
        AtomicInteger metadata = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> respond(exchange, 200, "[]"));
        catalog(server, "/index");
        server.createContext("/index", exchange -> {
            metadata.incrementAndGet();
            String query = decodedQuery(exchange);
            assertTrue(query.contains("url=hust.edu.vn/&matchType=domain")); assertFalse(query.contains("*"));
            respond(exchange, 200, "{\"pages\":0}");
        });
        server.start(); MutableClock clock = new MutableClock();
        try {
            Path directory = temp.resolve("passive-discovery"); Files.createDirectories(directory);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Files.writeString(directory.resolve("commoncrawl-state.json"), "{\"hosts\":[\"historical.hust.edu.vn\"],"
                    + "\"complete\":true,\"errors\":[],\"checkedAt\":" + clock.millis() + ",\"nextCheckAt\":"
                    + clock.instant().plus(Duration.ofHours(24)).toEpochMilli() + ",\"endpoint\":\"" + base
                    + "/collections\",\"cursor\":\"\",\"indexEndpoint\":\"" + base + "/index\",\"nextPage\":0,\"totalPages\":0}");
            var refreshed = source(server, 3, clock).discover(temp);
            assertTrue(refreshed.complete()); assertEquals(Set.of("historical.hust.edu.vn"), refreshed.hosts());
            assertEquals(1, metadata.get());
            assertTrue(Files.readString(directory.resolve("commoncrawl-state.json")).contains("commoncrawl-domain-v2"));
            assertTrue(source(server, 3, clock).discover(temp).complete()); assertEquals(1, metadata.get());
        } finally { server.stop(0); }
    }

    @Test void queryMigrationDoesNotBypassCommonCrawlOrWaybackRateLimitDeadlines() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/wayback", exchange -> { requests.incrementAndGet(); respond(exchange, 200, "[]"); });
        server.createContext("/collections", exchange -> { requests.incrementAndGet(); respond(exchange, 200, catalogJson(server, "/index")); });
        server.createContext("/index", exchange -> { requests.incrementAndGet(); respond(exchange, 200, "{\"pages\":0}"); });
        server.start(); MutableClock clock = new MutableClock();
        try {
            Path directory = temp.resolve("passive-discovery"); Files.createDirectories(directory);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            long retryAt = clock.instant().plus(Duration.ofHours(1)).toEpochMilli();
            String wbState = "{\"hosts\":[],\"complete\":false,\"errors\":[\"wayback: HTTP429\"],\"nextCheckAt\":"
                    + retryAt + ",\"endpoint\":\"" + base + "/wayback\",\"cursor\":\"resume\",\"nextPage\":0,\"totalPages\":-1}";
            Files.writeString(directory.resolve("wayback-state.json"), wbState);
            Files.writeString(directory.resolve("commoncrawl-state.json"), "{\"hosts\":[\"historical.hust.edu.vn\"],"
                    + "\"complete\":false,\"errors\":[\"commoncrawl: HTTP429\"],\"nextCheckAt\":" + retryAt
                    + ",\"endpoint\":\"" + base + "/collections\",\"indexEndpoint\":\"" + base + "/index\",\"nextPage\":1,\"totalPages\":2}");
            var blocked = source(server, 3, clock).discover(temp);
            assertFalse(blocked.complete()); assertEquals(Set.of("historical.hust.edu.vn"), blocked.hosts());
            assertEquals(0, requests.get()); assertEquals(wbState, Files.readString(directory.resolve("wayback-state.json")));
            clock.advance(Duration.ofMinutes(59)); source(server, 3, clock).discover(temp); assertEquals(0, requests.get());
            clock.advance(Duration.ofMinutes(2)); assertTrue(source(server, 3, clock).discover(temp).complete());
            assertEquals(3, requests.get());
        } finally { server.stop(0); }
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }
    private static void catalog(HttpServer server, String path) {
        server.createContext("/collections", exchange -> respond(exchange, 200, catalogJson(server, path)));
    }
    private static String catalogJson(HttpServer server, String path) {
        return "[{\"id\":\"latest-dynamic\",\"cdx-api\":\"http://127.0.0.1:" + server.getAddress().getPort() + path + "\"}]";
    }
    private static void emptyCommonCrawl(HttpServer server) {
        catalog(server, "/index"); server.createContext("/index", exchange -> respond(exchange, 200, "{\"pages\":0}"));
    }
    private static ArchiveSubdomainDiscovery source(HttpServer server, int maxPages, Clock clock) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ArchiveSubdomainDiscovery(HttpClient.newHttpClient(), URI.create(base + "/wayback"),
                URI.create(base + "/collections"), maxPages, clock, Duration.ofHours(24), 0);
    }
    private static String decodedQuery(HttpExchange exchange) {
        return URLDecoder.decode(Objects.toString(exchange.getRequestURI().getRawQuery(), ""), StandardCharsets.UTF_8);
    }
    private static void respond(HttpExchange exchange, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-07T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
