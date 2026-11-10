package edu.hust.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Discovers scoped URLs from robots, sitemap XML and syndication feeds without network access. */
public final class DiscoveryFeeds {
    static final int MAX_URLS = 50_000;
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Pattern UNRESOLVED_ENTITY = Pattern.compile("&[A-Za-z_:][A-Za-z0-9_.:-]*;");

    private DiscoveryFeeds() {}

    public record Feed(Set<URI> pages, Set<URI> sitemaps) {
        public Feed {
            pages = Collections.unmodifiableSet(new LinkedHashSet<>(pages));
            sitemaps = Collections.unmodifiableSet(new LinkedHashSet<>(sitemaps));
        }
    }

    public static Set<URI> defaultSitemaps(URI page) {
        Set<URI> result = new LinkedHashSet<>();
        SubdomainDiscoverer.normalize(page.toString()).ifPresent(normalized -> {
            URI origin = SubdomainDiscoverer.origin(normalized);
            result.add(origin.resolve("/sitemap.xml"));
            result.add(origin.resolve("/sitemap_index.xml"));
        });
        return Collections.unmodifiableSet(result);
    }

    public static Set<URI> robotsSitemaps(String robotsText, URI origin) {
        Set<URI> result = new LinkedHashSet<>();
        for (String line : robotsText.split("\\R")) {
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            int colon = line.indexOf(':');
            if (colon < 0 || !line.substring(0, colon).trim().equalsIgnoreCase("sitemap")) continue;
            add(line.substring(colon + 1), origin, result);
            if (result.size() == MAX_URLS) break;
        }
        return Collections.unmodifiableSet(result);
    }

    public static Feed parse(byte[] bytes, URI base) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(base, "base");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("XML exceeds 20 MiB");
        Document xml;
        try {
            // Jsoup's XML tokenizer does not load external DTDs or expand user-defined entities.
            // Parsing the byte stream also preserves BOM/XML-declared charset handling.
            xml = Jsoup.parse(new ByteArrayInputStream(bytes), null, base.toString(), Parser.xmlParser());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot parse feed", e);
        }
        Set<URI> pages = new LinkedHashSet<>(), sitemaps = new LinkedHashSet<>();
        Element root = xml.children().first();
        if (root == null) return new Feed(pages, sitemaps);
        switch (localName(root)) {
            case "sitemapindex" -> locations(root, "sitemap", base, sitemaps);
            case "urlset" -> locations(root, "url", base, pages);
            case "rss", "rdf" -> rss(xml, base, pages);
            case "feed" -> atom(xml, base, pages);
            default -> { }
        }
        return new Feed(pages, sitemaps);
    }

    private static void locations(Element root, String itemName, URI base, Set<URI> result) {
        for (Element item : root.children()) {
            if (!localName(item).equals(itemName)) continue;
            for (Element child : item.children()) {
                if (localName(child).equals("loc")) add(child.text(), base, result);
                if (result.size() == MAX_URLS) return;
            }
        }
    }

    private static void rss(Document xml, URI base, Set<URI> pages) {
        for (Element element : xml.getAllElements()) {
            if (!localName(element).equals("item")) continue;
            for (Element child : element.children()) {
                String name = localName(child);
                if (name.equals("link")) {
                    add(child.hasAttr("href") ? child.attr("href") : child.text(), base, pages);
                } else if (name.equals("guid") && !child.attr("isPermaLink").equalsIgnoreCase("false")) {
                    add(child.text(), base, pages);
                }
                if (pages.size() == MAX_URLS) return;
            }
        }
    }

    private static void atom(Document xml, URI base, Set<URI> pages) {
        for (Element element : xml.getAllElements()) {
            // Feed-level next/previous links are useful for discovering the next XML page too.
            if (localName(element).equals("link") && element.hasAttr("href")) add(element.attr("href"), base, pages);
            if (pages.size() == MAX_URLS) return;
        }
    }

    public static Set<URI> htmlFeeds(Document document) {
        Set<URI> result = new LinkedHashSet<>();
        URI base;
        try { base = URI.create(document.baseUri()); }
        catch (IllegalArgumentException e) { return Set.of(); }
        for (Element link : document.select("link[href]")) {
            Set<String> relations = new HashSet<>(Arrays.asList(link.attr("rel").toLowerCase(Locale.ROOT).trim().split("\\s+")));
            String type = link.attr("type").toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            if (relations.contains("sitemap") || relations.contains("alternate")
                    && (type.equals("application/rss+xml") || type.equals("application/atom+xml")))
                add(link.absUrl("href"), base, result);
            if (result.size() == MAX_URLS) break;
        }
        return Collections.unmodifiableSet(result);
    }

    private static String localName(Element element) {
        String name = element.tagName();
        return name.substring(name.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    private static void add(String value, URI base, Set<URI> destination) {
        if (destination.size() >= MAX_URLS) return;
        String url = value.trim();
        if (url.isEmpty() || UNRESOLVED_ENTITY.matcher(url).find()) return;
        try { SubdomainDiscoverer.normalize(base.resolve(url).toString()).ifPresent(destination::add); }
        catch (IllegalArgumentException ignored) { }
    }
}
