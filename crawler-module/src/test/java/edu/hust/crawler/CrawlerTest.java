package edu.hust.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import static org.junit.jupiter.api.Assertions.*;

class CrawlerTest {
    @TempDir Path temp;

    @Test void schedulerContinuesAfterFailedRoundAndStopsCleanly() throws Exception {
        var rounds = new AtomicInteger();
        var completed = new java.util.concurrent.CountDownLatch(1);
        try (var scheduler = new CrawlerScheduler()) {
            assertThrows(IllegalArgumentException.class, () -> scheduler.start(() -> {}, java.time.Duration.ZERO));
            scheduler.start(() -> {
                if (rounds.incrementAndGet() == 1) throw new IllegalStateException("fixture failure");
                completed.countDown();
            }, java.time.Duration.ofMillis(10));
            assertTrue(completed.await(3, java.util.concurrent.TimeUnit.SECONDS));
        }
        int count = rounds.get();
        assertTrue(count >= 2);
    }

    @Test void domainBoundaryAndCanonicalization() {
        assertEquals("https://soict.hust.edu.vn/a%20b?q=1", SubdomainDiscoverer.normalize("HTTPS://SOICT.HUST.EDU.VN:443/a%20b?q=1#x").orElseThrow().toString());
        for (String bad : List.of("https://hust.edu.vn.evil.com/", "https://evilhust.edu.vn/", "file:///tmp/a", "https://user@hust.edu.vn/", "https://hust.edu.vn:8080/"))
            assertTrue(SubdomainDiscoverer.normalize(bad).isEmpty(), bad);
        var doc = Jsoup.parse("<a href='/article#one'>A</a><a href='https://sami.hust.edu.vn/'>B</a><a href='https://evil.com/'>X</a>", "https://hust.edu.vn/");
        assertEquals(Set.of("hust.edu.vn", "sami.hust.edu.vn"), new SubdomainDiscoverer().discover(doc));
    }

    @Test void discoversSubdomainsOutsideAnchorTagsWithoutAcceptingLookalikeDomains() {
        var doc = Jsoup.parse("""
                <iframe src="http://faculty.hust.edu.vn/directory"></iframe>
                <form action="//admissions.hust.edu.vn/apply"></form>
                <link href="https://assets.hust.edu.vn/style.css" rel="stylesheet">
                <script>const config = {"url":"https:\\/\\/api.hust.edu.vn/service"};</script>
                <a href="https://hust.edu.vn.evil.com/">Outside</a>
                <iframe src="javascript:alert(1)"></iframe>
                """, "https://hust.edu.vn/");
        assertEquals(Set.of("faculty.hust.edu.vn", "admissions.hust.edu.vn", "assets.hust.edu.vn", "api.hust.edu.vn"),
                new SubdomainDiscoverer().discover(doc));
        assertTrue(new SubdomainDiscoverer().entryPoints(doc).contains(URI.create("http://faculty.hust.edu.vn/directory")));
    }

    @Test void automaticallyDiscoversAcrossSitesAndResumesSavedDiscovery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Set<String> requested = new HashSet<>();
        StringBuilder home = new StringBuilder("<title>HUST</title>");
        for (int i = 0; i < 100; i++) home.append("<a href='/news/").append(i).append("'>Article</a>");
        home.append("<iframe src='http://faculty.hust.edu.vn/directory'></iframe>");
        server.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            requested.add(uri.toString());
            String content = ""; int code = 200;
            if (uri.getPath().equals("/robots.txt")) content = "User-agent: *\nAllow: /";
            else if (uri.getHost().equals("hust.edu.vn") && uri.getPath().equals("/")) content = home.toString();
            else if (uri.toString().equals("http://faculty.hust.edu.vn/directory"))
                content = "<title>Faculty</title><script>const site='http://research.hust.edu.vn/service';</script>";
            else if (uri.toString().equals("http://research.hust.edu.vn/")) content = "<title>Research</title>";
            else code = 404;
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        Path seedOutput = temp.resolve("resources/initial_subdomains.txt");
        Files.createDirectories(seedOutput.getParent());
        Files.writeString(seedOutput, "# Keep this comment\nhttp://hust.edu.vn/\n");
        var client = HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", server.getAddress().getPort())))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var seeds = List.of(URI.create("http://hust.edu.vn/"));
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            var first = new HustWebCrawler(temp, state, 0, client, seedOutput);
            assertEquals(1, first.crawl(seeds, 1, 10).saved());
            assertEquals(List.of("faculty.hust.edu.vn"), Files.readAllLines(temp.resolve("discovered_subdomains.txt")));
            assertTrue(Files.readString(seedOutput).contains("http://faculty.hust.edu.vn/directory"));
            assertFalse(requested.contains("http://faculty.hust.edu.vn/directory"));
            String edges = Files.readString(temp.resolve("links.csv"));
            assertTrue(edges.contains("\"http://hust.edu.vn/\",\"http://faculty.hust.edu.vn/directory\""));
            assertTrue(edges.contains("\"iframe\""));
            assertTrue(Files.readString(temp.resolve("crawl_urls.csv")).contains("\"http://hust.edu.vn/\""));

            // Remove the original link: a fresh crawler must recover it from its own saved registry.
            home.setLength(0); home.append("<title>HUST</title>");
            requested.clear();
            var resumed = new HustWebCrawler(temp, state, 0, client, seedOutput);
            var report = resumed.crawl(seeds, 3, 120);
            assertEquals(3, report.saved());
            assertEquals(Set.of("faculty.hust.edu.vn", "research.hust.edu.vn"), report.subdomains());
            assertTrue(requested.contains("http://faculty.hust.edu.vn/directory"));
            assertTrue(requested.contains("http://research.hust.edu.vn/"));
            assertFalse(requested.contains("http://faculty.hust.edu.vn/"));
            assertEquals("# Keep this comment", Files.readAllLines(seedOutput).get(0));
            assertTrue(Files.readString(seedOutput).contains("http://research.hust.edu.vn/"));
        } finally { server.stop(0); }
    }

    @Test void newDomainsArePrioritizedEvenWhenThePageQueueIsFull() {
        var frontier = new CrawlFrontier(2);
        frontier.add(URI.create("https://hust.edu.vn/news/1"));
        frontier.add(URI.create("https://hust.edu.vn/news/2"));
        frontier.add(URI.create("https://hust.edu.vn/news/3"));
        frontier.add(URI.create("http://faculty.hust.edu.vn/directory"));
        assertEquals("https://hust.edu.vn/news/1", frontier.next().toString());
        assertEquals("http://faculty.hust.edu.vn/directory", frontier.next().toString());
        assertEquals("https://hust.edu.vn/news/2", frontier.next().toString());
        assertTrue(frontier.isEmpty());
    }

    @Test void replacesLegacyRootWithWorkingDeepEntryAndKeepsItAcrossRestarts() throws Exception {
        Files.writeString(temp.resolve("discovered_subdomains.txt"), "faculty.hust.edu.vn\n");
        var store = new SubdomainStore(temp, null);
        URI working = URI.create("https://faculty.hust.edu.vn/directory");
        store.rememberFetched(working);
        store.remember(List.of(URI.create("https://faculty.hust.edu.vn/")));
        store.rememberFetched(URI.create("https://faculty.hust.edu.vn/article"));
        assertEquals(List.of(working), new SubdomainStore(temp, null).entryPoints());
    }

    @Test void hashesAndPersistentChangeDetection() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ChangeDetectionService.computeSHA256(""));
        Path db = temp.resolve("state.db"), raw = temp.resolve("a.html");
        String url = "https://hust.edu.vn/a";
        try (var state = new ChangeDetectionService(db)) {
            assertEquals(ChangeDetectionService.Status.NEW, state.record(url, "Tiếng Việt".getBytes(StandardCharsets.UTF_8), raw, "text/html", "A"));
        }
        try (var state = new ChangeDetectionService(db)) {
            assertEquals(ChangeDetectionService.Status.UNCHANGED, state.record(url, "Tiếng Việt".getBytes(StandardCharsets.UTF_8), raw, "text/html", "A"));
            assertEquals(ChangeDetectionService.Status.UPDATED, state.record(url, new byte[]{1}, raw, "text/html", "A"));
            assertEquals(1, state.count());
            Path manifest = temp.resolve("manifest.csv");
            state.exportManifest(manifest);
            String csv = Files.readString(manifest);
            assertTrue(csv.startsWith("id,url,title,"));
            assertTrue(csv.contains("\"https://hust.edu.vn/a\""));
            assertTrue(csv.contains("\"UPDATED\""));
        }
    }

    @Test void resourceDoesNotGrowWithEveryWorkingArticleFromTheSameOrigin() throws Exception {
        Path resource = temp.resolve("initial_subdomains.txt");
        Files.writeString(resource, "# Existing bootstrap\nhttps://hust.edu.vn/\n");
        for (int round = 1; round <= 3; round++) {
            var store = new SubdomainStore(temp, resource);
            store.rememberFetched(URI.create("https://hust.edu.vn/article-" + round));
            store.rememberFetched(URI.create("http://new.hust.edu.vn/working-" + round));
        }
        assertEquals(List.of("# Existing bootstrap", "https://hust.edu.vn/", "http://new.hust.edu.vn/working-1"),
                Files.readAllLines(resource));
        assertTrue(new SubdomainStore(temp, resource).entryPoints().contains(URI.create("http://new.hust.edu.vn/working-3")));
    }

    @Test void newWebDiscoveryUpdatesTheCombinedCatalogWithoutErasingOtherSources() throws Exception {
        Path catalog = temp.resolve("discovery/subdomains.txt");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, "archive-only.hust.edu.vn\n");
        new SubdomainStore(temp, null).rememberFetched(URI.create("http://web-only.hust.edu.vn/article"));
        assertEquals(List.of("archive-only.hust.edu.vn", "web-only.hust.edu.vn"), Files.readAllLines(catalog));
    }

    @Test void robotsSpecificGroupsWildcardsAndAllowTies() {
        var rules = RobotsRules.parse("User-agent: *\nDisallow: /private\nAllow: /private/open\nDisallow: /*.pdf$\nAllow: /ok.pdf$");
        assertFalse(rules.allowed("/private/a"));
        assertTrue(rules.allowed("/private/open/a"));
        assertFalse(rules.allowed("/a.pdf"));
        assertTrue(rules.allowed("/ok.pdf"));
        assertTrue(RobotsRules.parse("User-agent: *\nDisallow: /\nUser-agent: HustResearchCrawler\nAllow: /").allowed("/a"));
        assertTrue(RobotsRules.parse("User-agent: *\nAllow: /a\nDisallow: /a").allowed("/a"));
        assertTrue(RobotsRules.parse("User-agent: HustResearchCrawler\nCrawl-delay: 2\nUser-agent: evil\nDisallow: /").allowed("/a"));
    }

    @Test void honorsCrawlDelayForTheSelectedRobotGroup() {
        assertEquals(2500, RobotsRules.parse("User-agent: *\nCrawl-delay: 2.5\nAllow: /").crawlDelayMillis());
        assertEquals(1000, RobotsRules.parse("User-agent: *\nCrawl-delay: 10\nUser-agent: HustResearchCrawler\nCrawl-delay: 1").crawlDelayMillis());
        assertEquals(0, RobotsRules.parse("User-agent: *\nCrawl-delay: NaN\nCrawl-delay: -1\nCrawl-delay: nope").crawlDelayMillis());
    }

    @Test void crawlHtmlAttachmentRobotsRedirectAndRecrawl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger blocked = new AtomicInteger(), external = new AtomicInteger(), version = new AtomicInteger(), rateLimited = new AtomicInteger();
        server.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            if (!("hust.edu.vn".equals(uri.getHost()) || "sami.hust.edu.vn".equals(uri.getHost()))) external.incrementAndGet();
            String path = uri.getPath();
            String text; String type = "text/html; charset=UTF-8";
            switch (path) {
                case "/robots.txt" -> { type = "text/plain"; text = "User-agent: *\nDisallow: /blocked"; }
                case "/sitemap.xml", "/sitemap_index.xml" -> {
                    exchange.sendResponseHeaders(404, -1); exchange.close(); return;
                }
                case "/" -> text = "<title>HUST</title><a href='/article'>Bài viết</a><a href='/file.pdf'>PDF</a><a href='/blocked'>No</a><a href='/redirect'>Redirect</a><a href='http://evil.com/'>No</a><a href='http://sami.hust.edu.vn/rate'>Rate</a><a href='http://sami.hust.edu.vn/never'>Never</a>";
                case "/rate" -> {
                    rateLimited.incrementAndGet();
                    exchange.getResponseHeaders().add("Retry-After", "3600");
                    exchange.sendResponseHeaders(429, -1); exchange.close(); return;
                }
                case "/article" -> text = "<title>Bài viết</title>Nội dung " + version.get();
                case "/file.pdf" -> { type = "application/pdf"; text = "%PDF-1.4 fixture"; }
                case "/redirect" -> {
                    exchange.getResponseHeaders().add("Location", "http://evil.com/");
                    exchange.sendResponseHeaders(302, -1); exchange.close(); return;
                }
                default -> { blocked.incrementAndGet(); text = "blocked"; }
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try (var state = new ChangeDetectionService(temp.resolve("crawler.db"))) {
            var client = HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", server.getAddress().getPort())))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            var crawler = new HustWebCrawler(temp, state, 0, client);
            var seeds = List.of(URI.create("http://hust.edu.vn/"));
            assertEquals(3, new HustWebCrawler(temp, state, 0, client).crawl(seeds, 10, 20).saved());
            assertEquals(3, state.count());
            assertEquals(1, rateLimited.get());
            assertEquals(0, blocked.get()); assertEquals(0, external.get());
            try (var files = Files.list(temp.resolve("raw_files"))) { assertEquals(1, files.count()); }
            version.incrementAndGet();
            assertEquals(3, crawler.crawl(seeds, 10, 20).saved());
            assertEquals(3, state.count());
            assertEquals(1, rateLimited.get());
            assertEquals(0, blocked.get());
            assertEquals(1, crawler.crawl(seeds, 1, 20).saved());
            assertEquals(3, state.count());
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("crawler.db"));
                 var s = db.createStatement(); var r = s.executeQuery("SELECT status FROM crawled_pages WHERE url='http://hust.edu.vn/article'")) {
                assertTrue(r.next()); assertEquals("UPDATED", r.getString(1));
            }
        } finally { server.stop(0); }
    }
}
