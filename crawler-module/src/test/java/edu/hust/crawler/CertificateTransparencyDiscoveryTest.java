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
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CertificateTransparencyDiscoveryTest {
    @TempDir Path temp;

    @Test void validatesConcreteHostnamesWithoutInventingHostsFromWildcards() {
        assertEquals("deep.faculty.hust.edu.vn", CertificateTransparencyDiscovery.normalizeName(" Deep.Faculty.HUST.EDU.VN. ").orElseThrow());
        for (String invalid : new String[]{"hust.edu.vn", "*.hust.edu.vn", "faculty.hust.edu.vn.evil.com", "evil-hust.edu.vn",
                "https://faculty.hust.edu.vn", "person@faculty.hust.edu.vn", "bad_name.hust.edu.vn", "-bad.hust.edu.vn",
                "bad-.hust.edu.vn", "bad..hust.edu.vn", "a".repeat(64) + ".hust.edu.vn"})
            assertTrue(CertificateTransparencyDiscovery.normalizeName(invalid).isEmpty(), invalid);
    }

    @Test void mergesSourcesAndPaginatesThroughTheEmptyTerminalPage() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        HttpServer server = server(exchange -> {
            assertTrue(exchange.getRequestURI().getRawQuery().contains("q=%25.hust.edu.vn"));
            respond(exchange, 200, """
                    [{"name_value":"SOICT.HUST.EDU.VN\\n*.hust.edu.vn\\n*.dept.hust.edu.vn\\nhust.edu.vn\\nfaculty.hust.edu.vn.evil.com\\nbad.hust.edu.vn..",
                      "common_name":"common.hust.edu.vn."}]
                    """);
        }, exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("include_subdomains=true"));
            assertTrue(query.contains("expand=dns_names"));
            if (pages.incrementAndGet() == 1) {
                assertFalse(query.contains("after="));
                respond(exchange, 200, """
                        [{"id":"opaque cursor+1","dns_names":["soict.hust.edu.vn","api.hust.edu.vn","*.hust.edu.vn","outside.com"]}]
                        """);
            } else {
                assertTrue(query.contains("after=opaque cursor+1"));
                respond(exchange, 200, "[]");
            }
        });
        try {
            var result = discovery(server, 5).discover(temp);
            assertEquals(Set.of("soict.hust.edu.vn", "common.hust.edu.vn", "api.hust.edu.vn"), result.hosts());
            assertEquals(Set.of("*.hust.edu.vn", "*.dept.hust.edu.vn"), result.wildcards());
            assertTrue(result.complete()); assertTrue(result.errors().isEmpty());
            assertEquals(2, pages.get());
            assertTrue(Files.isRegularFile(temp.resolve("passive-discovery/crtsh-page-001.response")));
            assertTrue(Files.isRegularFile(temp.resolve("passive-discovery/certspotter-page-002.response")));
            assertTrue(Files.readString(temp.resolve("passive-discovery/source-status.txt")).contains("complete=true"));
            assertThrows(UnsupportedOperationException.class, () -> result.hosts().add("other.hust.edu.vn"));
        } finally { server.stop(0); }
    }

    @Test void preservesPartialResultsAndReportsSourceFailureAndRateLimit() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        HttpServer server = server(exchange -> respond(exchange, 502, "temporary upstream error"), exchange -> {
            if (pages.incrementAndGet() == 1)
                respond(exchange, 200, "[{\"id\":\"one\",\"dns_names\":[\"hidden.hust.edu.vn\"]}]");
            else {
                exchange.getResponseHeaders().add("Retry-After", "120");
                respond(exchange, 429, "{\"code\":\"rate_limited\"}");
            }
        });
        try {
            var result = discovery(server, 10).discover(temp);
            assertEquals(Set.of("hidden.hust.edu.vn"), result.hosts());
            assertFalse(result.complete()); assertEquals(2, result.errors().size());
            assertTrue(result.errors().get(0).contains("502"));
            assertTrue(result.errors().get(1).contains("429"));
            assertTrue(result.errors().get(1).contains("Retry-After=120"));
            assertEquals(2, pages.get());
            assertEquals("temporary upstream error", Files.readString(temp.resolve("passive-discovery/crtsh-page-001.response")));
            assertTrue(Files.readString(temp.resolve("passive-discovery/source-status.txt")).contains("complete=false"));
        } finally { server.stop(0); }
    }

    @Test void malformedOrNonArrayResponsesAreNotReportedAsSuccessfulEmptyResults() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "<html>Unavailable</html>"),
                exchange -> respond(exchange, 200, "{\"error\":\"unexpected schema\"}"));
        try {
            var result = discovery(server, 5).discover(temp);
            assertTrue(result.hosts().isEmpty()); assertFalse(result.complete());
            assertEquals(2, result.errors().size());
        } finally { server.stop(0); }
    }

    @Test void pageLimitAndRepeatedCursorsKeepCollectedHostsButMarkResultsPartial() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        HttpServer server = server(exchange -> respond(exchange, 200, "[]"), exchange -> {
            pages.incrementAndGet();
            respond(exchange, 200, "[{\"id\":\"same\",\"dns_names\":[\"page.hust.edu.vn\"]}]");
        });
        try {
            var limited = discovery(server, 1).discover(temp.resolve("limited"));
            assertFalse(limited.complete()); assertEquals(Set.of("page.hust.edu.vn"), limited.hosts());
            assertTrue(limited.errors().get(0).contains("page limit"));
            var repeated = discovery(server, 10).discover(temp.resolve("repeated"));
            assertFalse(repeated.complete()); assertEquals(Set.of("page.hust.edu.vn"), repeated.hosts());
            assertTrue(repeated.errors().get(0).contains("repeated pagination cursor"));
            assertEquals(3, pages.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsOversizedSourceBodyAndStillQueriesTheOtherSource() throws Exception {
        AtomicInteger fallback = new AtomicInteger();
        HttpServer server = server(exchange -> {
            int size = 20 * 1024 * 1024 + 1;
            exchange.sendResponseHeaders(200, size);
            try (var out = exchange.getResponseBody()) {
                byte[] bytes = new byte[8192];
                for (int sent = 0; sent < size; sent += bytes.length) out.write(bytes, 0, Math.min(bytes.length, size - sent));
            } catch (IOException ignored) { /* Client cancels at the configured cap. */ }
        }, exchange -> { fallback.incrementAndGet(); respond(exchange, 200, "[]"); });
        try {
            var result = discovery(server, 2).discover(temp);
            assertFalse(result.complete()); assertEquals(1, fallback.get());
            assertTrue(result.errors().stream().anyMatch(error -> error.contains("20 MiB")));
        } finally { server.stop(0); }
    }

    @Test void successfulSourcesUsePersistentIndependentRefreshTtls() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-07T00:00:00Z"));
        AtomicInteger crtCalls = new AtomicInteger(), certCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            crtCalls.incrementAndGet();
            respond(exchange, 200, "[{\"name_value\":\"cached.hust.edu.vn\"}]");
        }, exchange -> { certCalls.incrementAndGet(); respond(exchange, 200, "[]"); });
        try {
            assertTrue(discovery(server, clock, Duration.ofHours(6)).discover(temp).complete());
            clock.advance(Duration.ofMinutes(30));
            var cached = discovery(server, clock, Duration.ofHours(6)).discover(temp);
            assertTrue(cached.complete()); assertEquals(Set.of("cached.hust.edu.vn"), cached.hosts());
            assertEquals(1, crtCalls.get()); assertEquals(1, certCalls.get());
            assertTrue(Files.readString(temp.resolve("passive-discovery/source-status.txt")).contains("CACHE"));
            clock.advance(Duration.ofHours(6));
            assertTrue(discovery(server, clock, Duration.ofHours(6)).discover(temp).complete());
            assertEquals(2, crtCalls.get()); assertEquals(2, certCalls.get());
        } finally { server.stop(0); }
    }

    @Test void rateLimitSurvivesRestartWithoutBlockingTheOtherProvider() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-07T00:00:00Z"));
        AtomicInteger crtCalls = new AtomicInteger(), certCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            crtCalls.incrementAndGet();
            respond(exchange, 200, "[{\"name_value\":\"available.hust.edu.vn\"}]");
        }, exchange -> {
            certCalls.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "120");
            respond(exchange, 429, "{\"code\":\"rate_limited\"}");
        });
        try {
            assertFalse(discovery(server, clock, Duration.ZERO).discover(temp).complete());
            clock.advance(Duration.ofSeconds(119));
            var cached = discovery(server, clock, Duration.ZERO).discover(temp);
            assertFalse(cached.complete()); assertEquals(Set.of("available.hust.edu.vn"), cached.hosts());
            assertTrue(cached.errors().stream().anyMatch(error -> error.contains("429")));
            assertEquals(2, crtCalls.get()); assertEquals(1, certCalls.get());
            clock.advance(Duration.ofSeconds(2));
            discovery(server, clock, Duration.ZERO).discover(temp);
            assertEquals(3, crtCalls.get()); assertEquals(2, certCalls.get());
        } finally { server.stop(0); }
    }

    @Test void outageRetainsPriorNamesAndSuccessfulRawEvidenceDuringCooldown() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-07T00:00:00Z"));
        AtomicBoolean outage = new AtomicBoolean();
        AtomicInteger crtCalls = new AtomicInteger();
        String goodBody = "[{\"name_value\":\"historic.hust.edu.vn\"}]";
        HttpServer server = server(exchange -> {
            crtCalls.incrementAndGet(); respond(exchange, outage.get() ? 503 : 200, outage.get() ? "offline" : goodBody);
        }, exchange -> respond(exchange, 200, "[]"));
        try {
            assertTrue(discovery(server, clock, Duration.ZERO).discover(temp).complete());
            outage.set(true);
            var failed = discovery(server, clock, Duration.ZERO).discover(temp);
            assertFalse(failed.complete()); assertEquals(Set.of("historic.hust.edu.vn"), failed.hosts());
            assertEquals(goodBody, Files.readString(temp.resolve("passive-discovery/crtsh-page-001.response")));
            clock.advance(Duration.ofMinutes(14));
            assertFalse(discovery(server, clock, Duration.ZERO).discover(temp).complete());
            assertEquals(2, crtCalls.get());
            try (var evidence = Files.walk(temp.resolve("passive-discovery/history"))) {
                assertTrue(evidence.filter(Files::isRegularFile).anyMatch(file -> {
                    try { return Files.readString(file).equals("offline"); } catch (IOException e) { throw new RuntimeException(e); }
                }));
            }
            outage.set(false); clock.advance(Duration.ofMinutes(2));
            assertTrue(discovery(server, clock, Duration.ZERO).discover(temp).complete());
            assertEquals(3, crtCalls.get());
        } finally { server.stop(0); }
    }

    @Test void emptyPageRetryAfterDateIsRespectedAcrossNewInstances() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-07T00:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> respond(exchange, 200, "[]"), exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    ZonedDateTime.ofInstant(Instant.parse("2026-11-07T01:00:00Z"), ZoneOffset.UTC)));
            respond(exchange, 200, "[]");
        });
        try {
            assertTrue(discovery(server, clock, Duration.ZERO).discover(temp).complete());
            clock.advance(Duration.ofMinutes(59));
            assertTrue(discovery(server, clock, Duration.ZERO).discover(temp).complete());
            assertEquals(1, calls.get());
            clock.advance(Duration.ofMinutes(2));
            discovery(server, clock, Duration.ZERO).discover(temp);
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    private static CertificateTransparencyDiscovery discovery(HttpServer server, MutableClock clock, Duration refresh) {
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CertificateTransparencyDiscovery(HttpClient.newHttpClient(), URI.create(origin + "/crt"),
                URI.create(origin + "/certspotter"), 5, 0, clock, refresh);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private static HttpServer server(HttpHandler crt, HttpHandler certspotter) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/crt", crt); server.createContext("/certspotter", certspotter);
        server.start();
        return server;
    }

    private static CertificateTransparencyDiscovery discovery(HttpServer server, int maxPages) {
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CertificateTransparencyDiscovery(HttpClient.newHttpClient(), URI.create(origin + "/crt"),
                URI.create(origin + "/certspotter"), maxPages, 0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
