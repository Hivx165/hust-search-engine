package edu.hust.crawler;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative dataset labels and observed HUST links; ARTICLE is a heuristic, not a manual relevance label. */
public final class HtmlDatasetAnalyzer {
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}\\u200B\\uFEFF]+");
    private static final String BODY_MARKERS = "[itemprop=articleBody],.entry-content,#news-bodyhtml,.news-bodyhtml,.article-content,.article-body,.post-content";
    private static final Pattern ACCESS_PATH = Pattern.compile(
            "(?:^|/)(?:login|log-in|signin|sign-in|sign_in|auth|authenticate|authentication|oauth|oauth2|sso|admin|administrator|wp-admin|wp-login)(?:\\.(?:php|html?|aspx?))?(?:/|$)");
    private static final Pattern LISTING_PATH = Pattern.compile(
            "(?:^|/)(?:category|categories|tag|tags|author|authors|search|tim-kiem|calendar|lich-lam-viec)(?:\\.(?:php|html?|aspx?))?(?:/|$)|(?:^|/)page/\\d+(?:/|$)");
    private static final Pattern AUTHOR_LISTING = Pattern.compile("(?:^|/)(?:author|authors)(?:/|$)");
    private static final Pattern PROFILE_PATH = Pattern.compile(
            "(?:^|/)(?:profile|profiles|staff|people|personnel|faculty-members|can-bo|giang-vien|giao-vien|danh-sach-can-bo)(?:/|\\.|$)"
                    + "|(?:^|/)(?:pgs-ts|gs-ts|pgs|gs|ts|ths|ks|ksc)-[^/]+$");
    private static final Pattern LOGIN_TITLE = Pattern.compile(
            "^(?:login|log in|sign in|signin|đăng nhập|dang nhap|authentication required|authorization required)(?:\\s|$|[|:–-])");
    private static final Pattern ARTICLE_SCHEMA = Pattern.compile(
            "(?i)\"@type\"\\s*:\\s*(?:\"(?:Article|NewsArticle|BlogPosting|ScholarlyArticle)\""
                    + "|\\[[^\\]]{0,2048}\"(?:Article|NewsArticle|BlogPosting|ScholarlyArticle)\"[^\\]]{0,2048}\\])");
    private static final Set<String> ROOT_SECTIONS = Set.of("news", "tin-tuc", "thong-bao", "events", "su-kien", "search", "calendar");

    public record Link(URI target, String anchorText, String relation) {}
    public record Analysis(String kind, String reason, String title, int textLength, List<Link> links) {
        public Analysis { links = List.copyOf(links); }
    }

    public HtmlDatasetAnalyzer() {}

    public static Analysis analyze(Document document, URI url) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(url, "url");
        List<Link> links = extractLinks(document, url);
        String pageTitle = clean(document.title());
        Document textDocument = document.clone();
        // Article/main headers contain the actual headline, unlike the site's global header.
        for (Element header : textDocument.select("header")) {
            if (header.closest("article") == null && header.closest("main") == null && header.closest("[role=main]") == null)
                header.remove();
        }
        textDocument.select("nav,footer,script,style,aside,noscript,template,form,iframe,frame,[hidden],[aria-hidden=true],"
                + "[role=navigation],[role=banner],[role=contentinfo],.menu,.navbar,.navigation,.sidebar,.breadcrumb,.breadcrumbs,"
                + ".cookie-banner,.cookie-notice").remove();
        Element content = mainContent(textDocument);
        String text = content == null ? "" : clean(content.text());
        int length = text.length();
        Element headingElement = content == null ? null : content.selectFirst("h1");
        // WordPress often keeps the heading beside, rather than inside, .entry-content.
        if (headingElement == null) headingElement = textDocument.selectFirst("h1");
        String heading = headingElement == null ? "" : clean(headingElement.text());
        String path = Objects.toString(url.getPath(), "/").toLowerCase(Locale.ROOT);
        String titleForChecks = (pageTitle + " " + heading).toLowerCase(Locale.ROOT);
        String chosen = !heading.isEmpty() ? heading : pageTitle;

        boolean authorListing = AUTHOR_LISTING.matcher(path).find();
        if (ACCESS_PATH.matcher(path).find() && !authorListing)
            return result("ACCESS_PAGE", "AUTHENTICATION_OR_ADMIN_PATH", pageTitle, heading, length, links);
        if (!document.select("form input[type=password]").isEmpty())
            return result("ACCESS_PAGE", "PASSWORD_FORM", pageTitle, heading, length, links);
        if (!authorListing && (LOGIN_TITLE.matcher(pageTitle.toLowerCase(Locale.ROOT)).find()
                || LOGIN_TITLE.matcher(heading.toLowerCase(Locale.ROOT)).find()))
            return result("ACCESS_PAGE", "LOGIN_TITLE", pageTitle, heading, length, links);
        String placeholder = placeholderReason(document, titleForChecks, text.toLowerCase(Locale.ROOT));
        if (placeholder != null) return result("PLACEHOLDER", placeholder, pageTitle, heading, length, links);
        if (length < 50) return result("EMPTY", "NO_SUBSTANTIVE_NON_NAVIGATION_TEXT", pageTitle, heading, length, links);
        if (isRoot(path)) return result("LISTING", "SITE_OR_LANGUAGE_ROOT", pageTitle, heading, length, links);
        if (isListing(path, url)) return result("LISTING", "LISTING_SEARCH_OR_CALENDAR_PATH", pageTitle, heading, length, links);
        if (articleCardListing(textDocument, content)) return result("LISTING", "MULTIPLE_ARTICLE_CARDS", pageTitle, heading, length, links);
        if (linkHeavy(content, length)) return result("LISTING", "LINK_DOMINATED_CONTENT", pageTitle, heading, length, links);
        boolean detail = detailPath(path, url);
        if (content != null && content.select("article").size() >= 3 && !detail)
            return result("LISTING", "MULTIPLE_ARTICLE_CARDS", pageTitle, heading, length, links);
        if (PROFILE_PATH.matcher(path).find() || informationalPath(path)) {
            return new Analysis(length >= 150 ? "CONTENT_PAGE" : "EMPTY", "PROFILE_OR_INFORMATION_PAGE", chosen, length, links);
        }
        if (length >= 150 && detail && articleMarker(document))
            return new Analysis("ARTICLE", "ARTICLE_MARKER_AND_SUBSTANTIVE_DETAIL_TEXT", chosen, length, links);
        String leaf = leaf(path);
        if (length >= 600 && detail && leaf.matches(".{12,}\\.html?") && !chosen.isEmpty())
            return new Analysis("ARTICLE", "LONG_HTML_DETAIL_WITH_TITLE", chosen, length, links);
        if (length >= 150) return new Analysis("CONTENT_PAGE", "SUBSTANTIVE_TEXT_WITHOUT_CLEAR_ARTICLE_EVIDENCE", chosen, length, links);
        return result("EMPTY", "INSUFFICIENT_NON_NAVIGATION_TEXT", pageTitle, heading, length, links);
    }

    private static Analysis result(String kind, String reason, String title, String heading, int length, List<Link> links) {
        return new Analysis(kind, reason, title.isEmpty() ? heading : title, length, links);
    }

    private static Element mainContent(Document document) {
        Element marked = longest(document.select(BODY_MARKERS));
        if (marked != null) return marked;
        Elements articles = document.select("article");
        if (articles.size() == 1) return articles.first();
        Element main = longest(document.select("main,[role=main]"));
        return main != null ? main : document.body();
    }

    private static Element longest(Elements candidates) {
        Element best = null;
        int length = -1;
        for (Element candidate : candidates) {
            int current = clean(candidate.text()).length();
            if (current > length) { best = candidate; length = current; }
        }
        return best;
    }

    private static String placeholderReason(Document document, String title, String text) {
        if (title.contains("account suspended") || title.contains("website suspended") || text.startsWith("this account has been suspended"))
            return "SUSPENDED_ACCOUNT";
        if (title.contains("welcome to nginx") || title.contains("apache2 ubuntu default page")
                || title.contains("apache http server test page") || title.contains("iis windows server")
                || title.contains("default web site page") || title.contains("domain default page")
                || title.contains("centos webpanel") || title.contains("centos web panel")
                || title.contains("test page") && (title.contains("apache") || title.contains("nginx") || title.contains("centos"))
                || title.contains("testing 123") && (text.contains("centos") || text.contains("apache") || text.contains("nginx"))
                || title.strip().equals("it works!") && (text.contains("apache") || text.contains("nginx")))
            return "DEFAULT_SERVER_OR_CONTROL_PANEL_PAGE";
        if (title.contains("just a moment") || title.contains("attention required") && title.contains("cloudflare")
                || !document.select("#challenge-running,#challenge-form,#challenge-error-text,.cf-browser-verification").isEmpty()
                || text.startsWith("checking your browser before accessing"))
            return "BROWSER_OR_CLOUDFLARE_CHALLENGE";
        if (title.matches("(?s).*(?:^|\\s)(?:403 forbidden|404 not found|500 internal server error|502 bad gateway|503 service unavailable|page not found|trang không tồn tại|trang khong ton tai)(?:\\s|$).*"))
            return "HTTP_ERROR_PAGE";
        if (text.length() < 800 && (title.contains("website under maintenance") || title.contains("site under maintenance")
                || title.contains("under construction") || title.contains("coming soon"))) return "MAINTENANCE_PLACEHOLDER";
        return null;
    }

    private static boolean articleMarker(Document document) {
        for (Element meta : document.select("meta[property],meta[name]")) {
            String key = (meta.hasAttr("property") ? meta.attr("property") : meta.attr("name")).toLowerCase(Locale.ROOT);
            String value = meta.attr("content").strip();
            if (key.equals("og:type") && value.equalsIgnoreCase("article")) return true;
            if (key.equals("article:published_time") && !value.isEmpty()) return true;
        }
        if (!document.select(BODY_MARKERS).isEmpty() || document.select("article").size() == 1) return true;
        for (Element element : document.select("[itemtype]")) {
            if (element.attr("itemtype").toLowerCase(Locale.ROOT)
                    .matches(".*schema\\.org[/#](?:article|newsarticle|blogposting|scholarlyarticle)(?:\\s.*)?$")) return true;
        }
        for (Element script : document.select("script[type=application/ld+json]"))
            if (ARTICLE_SCHEMA.matcher(script.data()).find()) return true;
        return false;
    }

    private static boolean isRoot(String path) {
        return path.isEmpty() || path.equals("/") || path.matches("/(?:vi|vn|en|vi-vn|en-us)/?")
                || path.matches("/(?:(?:vi|vn|en|vi-vn|en-us)/)?(?:index|default|home)(?:\\.(?:html?|aspx?|php))?/?");
    }

    private static boolean articleCardListing(Document document, Element content) {
        int cards = 0;
        boolean primaryArticle = false;
        for (Element article : document.select("article")) {
            boolean heading = article.selectFirst("h1") != null;
            if (heading && clean(article.text()).length() >= 150) primaryArticle = true;
            if (!heading && !article.select("h2 a[href],h3 a[href]").isEmpty()) cards++;
        }
        if (cards < 3 || primaryArticle) return false;
        // A dedicated article body outside related cards still identifies an actual detail page.
        return content == null || content.closest("article") != null || !content.is(BODY_MARKERS);
    }

    private static boolean isListing(String path, URI url) {
        if (LISTING_PATH.matcher(path).find()) return true;
        String leaf = leaf(path).replaceFirst("\\.(?:html?|aspx?|php)$", "");
        if (ROOT_SECTIONS.contains(leaf)) return true;
        String query = url.getRawQuery();
        if (query != null) for (String parameter : query.split("&")) {
            try {
                String key = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (Set.of("q", "s", "search", "keyword", "keywords", "calendar", "month", "year", "page", "paged").contains(key)) return true;
            } catch (IllegalArgumentException ignored) { }
        }
        return false;
    }

    private static boolean informationalPath(String path) {
        return path.matches("(?:.*/)?(?:about|about-us|gioi-thieu|tong-quan|co-cau-to-chuc|lien-he|contact|contact-us)(?:/|\\.[^/]+)?$");
    }

    private static boolean detailPath(String path, URI url) {
        String leaf = leaf(path);
        if (leaf.matches(".+\\.(?:html?|aspx?|php)")) return true;
        if (path.matches(".*/(?:article|articles|post|posts|story|stories|detail|details|view)/[^/]+/?$")) return true;
        if (leaf.matches("[0-9]{3,}")) return true;
        if (leaf.length() >= 12 && leaf.chars().filter(character -> character == '-').count() >= 2) return true;
        String query = Objects.toString(url.getRawQuery(), "").toLowerCase(Locale.ROOT);
        return query.matches("(?:.*&)?(?:article_id|news_id|post_id|story_id|id)=.+");
    }

    private static String leaf(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static boolean linkHeavy(Element content, int length) {
        if (content == null || length == 0) return false;
        Elements anchors = content.select("a[href]");
        if (anchors.size() < 5) return false;
        int anchorLength = 0;
        for (Element anchor : anchors) anchorLength += clean(anchor.text()).length();
        return anchorLength > length * 0.7;
    }

    private static List<Link> extractLinks(Document document, URI source) {
        URI base = source;
        Element baseElement = document.selectFirst("base[href]");
        if (baseElement != null) {
            try { base = source.resolve(baseElement.attr("href").strip()); }
            catch (IllegalArgumentException ignored) { }
        }
        Map<String, Link> links = new LinkedHashMap<>();
        for (Element element : document.select("a[href],area[href],iframe[src]")) {
            boolean frame = element.normalName().equals("iframe");
            String raw = element.attr(frame ? "src" : "href").strip();
            if (raw.isEmpty()) continue;
            try {
                Optional<URI> target = SubdomainDiscoverer.normalize(base.resolve(raw).toString());
                if (target.isEmpty()) continue;
                String relation = frame ? "iframe" : element.normalName().equals("area") ? "area" : "anchor";
                String text = frame ? element.attr("title") : element.normalName().equals("area") ? element.attr("alt") : element.text();
                if (clean(text).isEmpty()) text = element.attr("aria-label");
                if (clean(text).isEmpty()) text = element.attr("title");
                if (clean(text).isEmpty() && !frame) {
                    Element image = element.selectFirst("img[alt]");
                    if (image != null) text = image.attr("alt");
                }
                String key = relation + "\n" + target.get();
                Link link = new Link(target.get(), clean(text), relation);
                Link previous = links.get(key);
                if (previous == null || previous.anchorText().isEmpty() && !link.anchorText().isEmpty()) links.put(key, link);
            } catch (IllegalArgumentException ignored) { }
        }
        return List.copyOf(links.values());
    }

    private static String clean(String value) { return WHITESPACE.matcher(value).replaceAll(" ").strip(); }
}
