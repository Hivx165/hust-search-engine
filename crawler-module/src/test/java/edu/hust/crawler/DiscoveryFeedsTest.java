package edu.hust.crawler;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryFeedsTest {
    private static final URI BASE = URI.create("https://hust.edu.vn/");
    @TempDir Path temp;

    @Test void defaultSitemapsUseTheScopedPageOrigin() {
        assertEquals(Set.of(URI.create("http://soict.hust.edu.vn/sitemap.xml"),
                        URI.create("http://soict.hust.edu.vn/sitemap_index.xml")),
                DiscoveryFeeds.defaultSitemaps(URI.create("http://soict.hust.edu.vn/news/article")));
        assertTrue(DiscoveryFeeds.defaultSitemaps(URI.create("https://hust.edu.vn.evil.com/")).isEmpty());
    }

    @Test void robotsAcceptsDeclaredHustSitemapsAcrossSubdomainsAndRejectsOtherDirectives() {
        String robots = """
                User-agent: *
                Disallow: /private
                Sitemap: /sitemap.xml # first index
                sitemap: https://soict.hust.edu.vn/sitemap_index.xml
                Sitemap: https://hust.edu.vn.evil.com/sitemap.xml
                Sitemap: https://user@hust.edu.vn/sitemap.xml
                Sitemap: javascript:alert(1)
                # Sitemap: https://hidden.hust.edu.vn/sitemap.xml
                """;
        assertEquals(Set.of(URI.create("https://hust.edu.vn/sitemap.xml"),
                URI.create("https://soict.hust.edu.vn/sitemap_index.xml")), DiscoveryFeeds.robotsSitemaps(robots, BASE));
    }

    @Test void sitemapIndexReturnsNestedIndexesAndGzipLocationsWithoutInventingPages() {
        var feed = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <sm:sitemapindex xmlns:sm="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sm:sitemap><sm:loc>https://hust.edu.vn/nested-index.xml</sm:loc></sm:sitemap>
                  <sm:sitemap><sm:loc>https://soict.hust.edu.vn/articles.xml.gz</sm:loc></sm:sitemap>
                  <sm:sitemap><sm:loc>https://outside.com/sitemap.xml</sm:loc></sm:sitemap>
                </sm:sitemapindex>
                """);
        assertTrue(feed.pages().isEmpty());
        assertEquals(Set.of(URI.create("https://hust.edu.vn/nested-index.xml"),
                URI.create("https://soict.hust.edu.vn/articles.xml.gz")), feed.sitemaps());
    }

    @Test void urlsetKeepsUtf8AndUnescapesQuerySeparatorsButIgnoresImageLocations() {
        var feed = parse("""
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="urn:image">
                  <url><loc>https://hust.edu.vn/tìm-kiếm?q=học&amp;lang=vi#section</loc>
                    <image:image><image:loc>https://images.hust.edu.vn/photo.jpg</image:loc></image:image>
                  </url>
                  <url><loc>https://hust.edu.vn/tìm-kiếm?q=học&amp;lang=vi</loc></url>
                  <url><loc>https://user@hust.edu.vn/private</loc></url>
                </urlset>
                """);
        assertEquals(Set.of(URI.create("https://hust.edu.vn/tìm-kiếm?q=học&lang=vi")), feed.pages());
        assertTrue(feed.sitemaps().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> feed.pages().add(BASE));
    }

    @Test void rssUsesArticleLinksAndPermalinkGuidsWithoutAcceptingOpaqueIdentifiers() {
        var feed = parse("""
                <rss version="2.0"><channel>
                  <title>HUST</title><link>https://hust.edu.vn/</link>
                  <item><link>https://hust.edu.vn/news/one?a=1&amp;b=2</link><guid isPermaLink="false">opaque-id</guid></item>
                  <item><guid isPermaLink="true">https://soict.hust.edu.vn/news/two</guid></item>
                  <item><guid>https://sme.hust.edu.vn/news/three</guid></item>
                  <item><link>https://outside.com/news</link></item>
                </channel></rss>
                """);
        assertEquals(Set.of(URI.create("https://hust.edu.vn/news/one?a=1&b=2"),
                URI.create("https://soict.hust.edu.vn/news/two"), URI.create("https://sme.hust.edu.vn/news/three")), feed.pages());
        assertTrue(feed.sitemaps().isEmpty());
    }

    @Test void atomUsesEntryHrefsAndFeedPaginationLinksWithinHust() {
        var feed = parse("""
                <atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
                  <atom:link rel="next" href="/feed?page=2&amp;lang=vi"/>
                  <atom:entry><atom:link rel="alternate" href="https://soict.hust.edu.vn/article"/></atom:entry>
                  <atom:entry><atom:link href="javascript:alert(1)"/></atom:entry>
                </atom:feed>
                """);
        assertEquals(Set.of(URI.create("https://hust.edu.vn/feed?page=2&lang=vi"),
                URI.create("https://soict.hust.edu.vn/article")), feed.pages());
    }

    @Test void htmlDeclaresRssAtomAndSitemapFeedsWithoutTreatingOtherAlternatesAsFeeds() {
        var html = Jsoup.parse("""
                <link rel="alternate" type="application/rss+xml" href="/feed.xml">
                <link rel="alternate" type="application/atom+xml; charset=UTF-8" href="https://soict.hust.edu.vn/atom.xml">
                <link rel="sitemap" href="/sitemap-index.xml">
                <link rel="alternate" type="text/html" href="/en/">
                <link rel="alternate" type="application/rss+xml" href="https://outside.com/rss">
                """, BASE.toString());
        assertEquals(Set.of(URI.create("https://hust.edu.vn/feed.xml"),
                URI.create("https://soict.hust.edu.vn/atom.xml"), URI.create("https://hust.edu.vn/sitemap-index.xml")),
                DiscoveryFeeds.htmlFeeds(html));
    }

    @Test void externalEntityDeclarationsNeverReadFilesOrMakeNetworkRequests() throws Exception {
        Path secret = temp.resolve("secret.txt");
        Files.writeString(secret, "https://secret.hust.edu.vn/confidential");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/entity", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            String xml = "<!DOCTYPE urlset [<!ENTITY file SYSTEM '" + secret.toUri()
                    + "'><!ENTITY remote SYSTEM 'http://127.0.0.1:" + server.getAddress().getPort() + "/entity'>]>"
                    + "<urlset><url><loc>&file;</loc></url><url><loc>&remote;</loc></url></urlset>";
            var feed = parse(xml);
            assertTrue(feed.pages().isEmpty()); assertTrue(feed.sitemaps().isEmpty());
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void feedSizeAndUrlCountAreBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> DiscoveryFeeds.parse(new byte[20 * 1024 * 1024 + 1], BASE));
        StringBuilder xml = new StringBuilder("<urlset>");
        for (int i = 0; i < 50_005; i++) xml.append("<url><loc>https://hust.edu.vn/news/").append(i).append("</loc></url>");
        xml.append("</urlset>");
        var feed = parse(xml.toString());
        assertEquals(50_000, feed.pages().size());
        assertFalse(feed.pages().contains(URI.create("https://hust.edu.vn/news/50000")));
    }

    private static DiscoveryFeeds.Feed parse(String xml) {
        return DiscoveryFeeds.parse(xml.getBytes(StandardCharsets.UTF_8), BASE);
    }
}
