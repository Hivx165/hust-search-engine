package edu.hust.crawler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class LiveHostProbeTest {
    @TempDir Path temp;
    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");

    @Test void boundedRoundsResumeUncheckedHostsAndCacheHeadEvidenceAcrossRestarts() throws Exception {
        AtomicInteger heads = new AtomicInteger();
        try (Fixture fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) reply(exchange, 200, "User-agent: *\nAllow: /");
            else { heads.incrementAndGet(); reply(exchange, 200, ""); }
        })) {
            var dns = List.of(resolved("a.hust.edu.vn"), resolved("b.hust.edu.vn"),
                    new DnsSubdomainVerifier.Entry("c.hust.edu.vn", "UNRESOLVED", Set.of()),
                    resolved("hust.edu.vn.evil.com"));
            var clock = new MutableClock();
            var first = probe(fixture.client, clock, 1).probe(dns, temp);
            assertEquals(3, first.size());
            assertEquals("HTTP_2XX", row(first, "a.hust.edu.vn").status());
            assertEquals("", row(first, "a.hust.edu.vn").title()); // HEAD does not invent page titles.
            assertEquals("NOT_CHECKED_YET", row(first, "b.hust.edu.vn").status());
            assertEquals("DNS_UNRESOLVED", row(first, "c.hust.edu.vn").status());
            assertEquals(1, heads.get());
            var second = probe(fixture.client, clock, 1).probe(dns, temp);
            assertEquals("HTTP_2XX", row(second, "b.hust.edu.vn").status());
            assertEquals(2, heads.get());
            probe(fixture.client, clock, 1).probe(dns, temp);
            assertEquals(2, heads.get());
            assertTrue(Files.isRegularFile(temp.resolve("discovery/live-hosts-state.json")));
            assertTrue(Files.readString(temp.resolve("discovery/live-hosts.csv")).startsWith("host,ip,http_status,final_url,title,status,checked_at\n"));
        }
    }

    @Test void robotsDeniedRootReceivesNoHeadOrPageGet() throws Exception {
        AtomicInteger pages = new AtomicInteger(), robots = new AtomicInteger();
        try (Fixture fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) {
                robots.incrementAndGet(); reply(exchange, 200, "User-agent: HustResearchCrawler\nDisallow: /");
            } else { pages.incrementAndGet(); reply(exchange, 200, "should never be requested"); }
        })) {
            var result = probe(fixture.client, new MutableClock(), 1).probe(List.of(resolved("denied.hust.edu.vn")), temp);
            assertEquals("ROBOTS_DENIED", result.get(0).status());
            assertEquals(0, result.get(0).httpStatus());
            assertEquals(0, pages.get()); assertEquals(1, robots.get());
        }
    }

    @Test void head405UsesRobotsApprovedBoundedGetAndEscapesCsvTitle() throws Exception {
        AtomicInteger robots = new AtomicInteger(), pageGets = new AtomicInteger();
        try (Fixture fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) {
                robots.incrementAndGet(); reply(exchange, 200, "User-agent: *\nAllow: /");
            } else if (exchange.getRequestMethod().equals("HEAD")) reply(exchange, 405, "");
            else {
                pageGets.incrementAndGet();
                reply(exchange, 200, "<title>Thông báo, &quot;HUST&quot;\nLine</title>");
            }
        })) {
            var row = probe(fixture.client, new MutableClock(), 1).probe(List.of(resolved("get.hust.edu.vn")), temp).get(0);
            assertEquals(200, row.httpStatus()); assertEquals("HTTP_2XX", row.status());
            assertEquals("Thông báo, \"HUST\" Line", row.title());
            assertEquals(1, robots.get()); assertEquals(1, pageGets.get());
            assertTrue(Files.readString(temp.resolve("discovery/live-hosts.csv")).contains("\"Thông báo, \"\"HUST\"\" Line\""));
        }
    }

    @Test void redirectBoundariesBlockOutsideHostsCredentialsAndNonDefaultPorts() throws Exception {
        Set<String> requested = ConcurrentHashMap.newKeySet();
        try (Fixture fixture = new Fixture(exchange -> {
            URI uri = exchange.getRequestURI(); requested.add(uri.getHost());
            if (uri.getPath().equals("/robots.txt")) { reply(exchange, 404, ""); return; }
            String target = switch (uri.getHost()) {
                case "start.hust.edu.vn" -> "http://target.hust.edu.vn/final";
                case "outside.hust.edu.vn" -> "https://outside.example/";
                case "credentials.hust.edu.vn" -> "http://user@target.hust.edu.vn/";
                case "port.hust.edu.vn" -> "http://target.hust.edu.vn:8080/";
                default -> "";
            };
            if (target.isEmpty()) reply(exchange, 200, "");
            else { exchange.getResponseHeaders().add("Location", target); reply(exchange, 302, ""); }
        })) {
            var result = probe(fixture.client, new MutableClock(), 4).probe(List.of(resolved("start.hust.edu.vn"),
                    resolved("outside.hust.edu.vn"), resolved("credentials.hust.edu.vn"), resolved("port.hust.edu.vn")), temp);
            assertEquals("http://target.hust.edu.vn/final", row(result, "start.hust.edu.vn").finalUrl());
            for (String host : List.of("outside.hust.edu.vn", "credentials.hust.edu.vn", "port.hust.edu.vn"))
                assertEquals("REDIRECT_BLOCKED", row(result, host).status());
            assertFalse(requested.contains("outside.example"));
        }
    }

    @Test void rateLimitIsDurableAndDoesNotTriggerHttpOrGetRetries() throws Exception {
        AtomicInteger heads = new AtomicInteger(), pageGets = new AtomicInteger();
        AtomicBoolean limited = new AtomicBoolean(true);
        MutableClock clock = new MutableClock();
        try (Fixture fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) { reply(exchange, 404, ""); return; }
            if (exchange.getRequestMethod().equals("HEAD")) heads.incrementAndGet(); else pageGets.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "7200");
            reply(exchange, limited.get() ? 429 : 200, "");
        })) {
            var first = probe(fixture.client, clock, 1).probe(List.of(resolved("limited.hust.edu.vn")), temp).get(0);
            assertEquals(429, first.httpStatus()); assertEquals("HTTP_429", first.status());
            clock.advance(Duration.ofMinutes(90));
            probe(fixture.client, clock, 1).probe(List.of(resolved("limited.hust.edu.vn")), temp);
            assertEquals(1, heads.get()); assertEquals(0, pageGets.get());
            try (var frontier = new PersistentCrawlFrontier(temp.resolve("frontier.db"), Duration.ofHours(1), clock)) {
                assertTrue(frontier.deferredUntil(URI.create("http://limited.hust.edu.vn/")).isPresent());
                assertTrue(frontier.deferredUntil(URI.create("https://limited.hust.edu.vn/")).isPresent());
            }
            clock.advance(Duration.ofMinutes(31)); limited.set(false);
            assertEquals(200, probe(fixture.client, clock, 1).probe(List.of(resolved("limited.hust.edu.vn")), temp).get(0).httpStatus());
            assertEquals(2, heads.get());
        }
    }

    @Test void sharedFrontierCooldownSkipsEveryNetworkRequestAnd503IsSharedBack() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger(), pageGets = new AtomicInteger();
        try (var frontier = new PersistentCrawlFrontier(temp.resolve("frontier.db"), Duration.ofHours(1), clock)) {
            frontier.deferOrigin(URI.create("http://cooldown.hust.edu.vn/"), START.plus(Duration.ofHours(2)));
        }
        try (Fixture fixture = new Fixture(exchange -> {
            requests.incrementAndGet();
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) reply(exchange, 404, "");
            else { if (!exchange.getRequestMethod().equals("HEAD")) pageGets.incrementAndGet(); reply(exchange, 503, ""); }
        })) {
            var result = probe(fixture.client, clock, 2).probe(List.of(resolved("cooldown.hust.edu.vn"), resolved("unavailable.hust.edu.vn")), temp);
            assertEquals("ORIGIN_COOLDOWN", row(result, "cooldown.hust.edu.vn").status());
            assertEquals(0, row(result, "cooldown.hust.edu.vn").httpStatus());
            assertEquals(503, row(result, "unavailable.hust.edu.vn").httpStatus());
            assertEquals(2, requests.get()); assertEquals(0, pageGets.get());
            try (var frontier = new PersistentCrawlFrontier(temp.resolve("frontier.db"), Duration.ofHours(1), clock)) {
                assertTrue(frontier.deferredUntil(URI.create("http://unavailable.hust.edu.vn/")).isPresent());
                assertTrue(frontier.deferredUntil(URI.create("https://unavailable.hust.edu.vn/")).isPresent());
            }
        }
    }

    @Test void advertisedCrawlDelayAppliesAfterRobotsBeforeHead() throws Exception {
        AtomicLong robotsAt = new AtomicLong(), headAt = new AtomicLong();
        try (Fixture fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) {
                robotsAt.set(System.nanoTime()); reply(exchange, 200, "User-agent: HustResearchCrawler\nCrawl-delay: 1\nAllow: /");
            } else { headAt.set(System.nanoTime()); reply(exchange, 200, ""); }
        })) {
            assertEquals(200, probe(fixture.client, new MutableClock(), 1).probe(List.of(resolved("polite.hust.edu.vn")), temp).get(0).httpStatus());
            assertTrue(headAt.get() - robotsAt.get() >= TimeUnit.MILLISECONDS.toNanos(850));
        }
    }

    @Test void httpsConnectFailureFallsBackBut403ResponseDoesNot() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) reply(exchange, 404, "");
            else reply(exchange, 403, "");
        })) {
            var failures = new RewritingClient(fixture.client, true);
            var fallback = new LiveHostProbe(failures, new MutableClock(), Duration.ofHours(1), 1, 0)
                    .probe(List.of(resolved("fallback.hust.edu.vn")), temp.resolve("fallback")).get(0);
            assertEquals("http://fallback.hust.edu.vn/", fallback.finalUrl());
            assertEquals(403, fallback.httpStatus());
            assertEquals(List.of("https", "http", "http"), failures.schemes);

            var workingTls = new RewritingClient(fixture.client, false);
            var forbidden = new LiveHostProbe(workingTls, new MutableClock(), Duration.ofHours(1), 1, 0)
                    .probe(List.of(resolved("forbidden.hust.edu.vn")), temp.resolve("forbidden")).get(0);
            assertEquals(403, forbidden.httpStatus());
            assertEquals("https://forbidden.hust.edu.vn/", forbidden.finalUrl());
            assertEquals(List.of("https", "https"), workingTls.schemes);
        }
    }

    @Test void oversizedGetBodyIsAnErrorAndNeverCalledSuccessful() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/robots.txt")) reply(exchange, 404, "");
            else if (exchange.getRequestMethod().equals("HEAD")) reply(exchange, 405, "");
            else {
                try { reply(exchange, 200, "x".repeat(64 * 1024 + 1)); }
                catch (IOException ignored) { /* The client cancels at the configured response limit. */ }
            }
        })) {
            var row = probe(fixture.client, new MutableClock(), 1).probe(List.of(resolved("large.hust.edu.vn")), temp).get(0);
            assertEquals(0, row.httpStatus());
            assertTrue(row.status().startsWith("NETWORK_ERROR:"));
        }
    }

    private static DnsSubdomainVerifier.Entry resolved(String host) { return new DnsSubdomainVerifier.Entry(host, "RESOLVED", Set.of("192.0.2.1")); }
    private static LiveHostProbe probe(HttpClient client, Clock clock, int count) {
        return new LiveHostProbe(client, clock, Duration.ofHours(1), count, 0, "http");
    }
    private static LiveHostProbe.Row row(List<LiveHostProbe.Row> rows, String host) { return rows.stream().filter(row -> row.host().equals(host)).findFirst().orElseThrow(); }
    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(status, -1); exchange.close(); return; }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server; final HttpClient client;
        Fixture(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler); server.start();
            client = HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", server.getAddress().getPort())))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
        @Override public void close() { server.stop(0); }
    }
    private static final class MutableClock extends Clock {
        private Instant instant = START;
        void advance(Duration value) { instant = instant.plus(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
    /** Tests real local HTTP transport while making TLS failure/response choices deterministic. */
    private static final class RewritingClient extends HttpClient {
        private final HttpClient delegate; private final boolean failTls;
        final List<String> schemes = new CopyOnWriteArrayList<>();
        RewritingClient(HttpClient delegate, boolean failTls) { this.delegate = delegate; this.failTls = failTls; }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            schemes.add(request.uri().getScheme());
            if (request.uri().getScheme().equals("https") && failTls) throw new SSLHandshakeException("fixture TLS failure");
            URI uri = URI.create("http://" + request.uri().getAuthority() + request.uri().getRawPath());
            var copy = HttpRequest.newBuilder(uri).method(request.method(), HttpRequest.BodyPublishers.noBody());
            request.headers().map().forEach((name, values) -> values.forEach(value -> copy.header(name, value)));
            return delegate.send(copy.build(), handler);
        }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                       HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
    }
}
