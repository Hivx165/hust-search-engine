package edu.hust.crawler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContinuousCrawlerTest {
    @TempDir Path temp;

    @Test void resumesSitemapsAndFeedsThenUsesConditionalGetAndFindsChangedContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger version = new AtomicInteger(1), notModified = new AtomicInteger();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String type = "text/html; charset=UTF-8", text;
            int code = 200;
            switch (path) {
                case "/robots.txt" -> {
                    type = "text/plain";
                    text = "User-agent: *\nAllow: /\nSitemap: http://hust.edu.vn/site-index.xml";
                }
                case "/" -> {
                    String etag = "\"v" + version.get() + "\"";
                    exchange.getResponseHeaders().add("ETag", etag);
                    exchange.getResponseHeaders().add("Last-Modified", "Sat, 07 Nov 2026 00:00:00 GMT");
                    if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                        notModified.incrementAndGet(); exchange.sendResponseHeaders(304, -1); exchange.close(); return;
                    }
                    text = "<title>HUST " + version.get() + "</title><link rel='alternate' type='application/rss+xml' href='/feed.xml'>"
                            + (version.get() == 2 ? "<a href='/new-article'>New</a>" : "");
                }
                case "/site-index.xml" -> {
                    type = "application/xml";
                    text = "<sitemapindex><sitemap><loc>http://hust.edu.vn/child.xml</loc></sitemap></sitemapindex>";
                }
                case "/child.xml" -> {
                    type = "application/xml";
                    text = "<urlset><url><loc>http://hust.edu.vn/hidden-article</loc></url></urlset>";
                }
                case "/feed.xml" -> {
                    type = "application/rss+xml";
                    text = "<rss><channel><item><link>http://school.hust.edu.vn/feed-article</link></item></channel></rss>";
                }
                case "/hidden-article", "/feed-article", "/new-article" -> text = "<title>Article</title>Public content " + version.get();
                default -> { code = 404; text = "Missing"; }
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(code, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var client = HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", server.getAddress().getPort())))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        List<URI> seeds = List.of(URI.create("http://hust.edu.vn/"));
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            assertEquals(1, new HustWebCrawler(temp, state, 0, client).crawl(seeds, 1, 50).saved());
            Path rootRaw = state.cachedPage(seeds.get(0).toString()).orElseThrow().path();
            var before = Files.getLastModifiedTime(rootRaw);
            var report = new HustWebCrawler(temp, state, 0, client).crawl(seeds, 20, 50);
            assertEquals(3, report.saved());
            assertTrue(notModified.get() >= 1);
            assertEquals(before, Files.getLastModifiedTime(rootRaw));
            assertTrue(state.cachedPage("http://hust.edu.vn/hidden-article").isPresent());
            assertTrue(state.cachedPage("http://school.hust.edu.vn/feed-article").isPresent());
            assertTrue(report.subdomains().contains("school.hust.edu.vn"));
            try (var feeds = Files.list(temp.resolve("raw_feeds"))) { assertTrue(feeds.count() >= 3); }
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("crawler.db"));
                 var s = db.createStatement(); var rows = s.executeQuery("SELECT http_status FROM crawl_http_state WHERE url='http://hust.edu.vn/'")) {
                assertTrue(rows.next()); assertEquals(304, rows.getInt(1));
            }
            version.set(2);
            new HustWebCrawler(temp, state, 0, client).crawl(seeds, 20, 50);
            assertTrue(Files.readString(rootRaw).contains("HUST 2"));
            assertTrue(state.cachedPage("http://hust.edu.vn/new-article").isPresent());
            assertTrue(Files.readString(temp.resolve("crawler_status.properties")).contains("round_running=false"));
        } finally { server.stop(0); }
    }

    @Test void dropsTrackingParametersButRetainsContentAndPaginationQueries() {
        assertEquals("https://hust.edu.vn/news?page=2&id=3", SubdomainDiscoverer.normalize(
                "https://hust.edu.vn/news?utm_source=mail&page=2&fbclid=abc&id=3#top").orElseThrow().toString());
    }

    @Test void persistedCooldownIsEnforcedForPageAndRobotsRedirectTargets() throws Exception {
        AtomicInteger forbiddenRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            if (uri.getHost().equals("paused.hust.edu.vn")) forbiddenRequests.incrementAndGet();
            if (uri.getHost().equals("robots-alias.hust.edu.vn") || !uri.getPath().equals("/robots.txt")) {
                exchange.getResponseHeaders().add("Location", "http://paused.hust.edu.vn" + uri.getPath());
                exchange.sendResponseHeaders(302, -1); exchange.close(); return;
            }
            byte[] bytes = "User-agent: *\nAllow: /".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try (var queue = new PersistentCrawlFrontier(temp.resolve("frontier.db"), java.time.Duration.ZERO)) {
            queue.deferOrigin(URI.create("http://paused.hust.edu.vn/"), java.time.Instant.now().plusSeconds(3600));
        }
        var client = HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", server.getAddress().getPort())))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        List<URI> seeds = List.of(URI.create("http://page-alias.hust.edu.vn/article"), URI.create("http://robots-alias.hust.edu.vn/"));
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            assertEquals(0, new HustWebCrawler(temp, state, 0, client).crawl(seeds, 5, 20).saved());
            assertEquals(0, forbiddenRequests.get());
            assertEquals(0, new HustWebCrawler(temp, state, 0, client).crawl(seeds, 5, 20).visited());
            assertEquals(0, forbiddenRequests.get());
        } finally { server.stop(0); }
    }
}
