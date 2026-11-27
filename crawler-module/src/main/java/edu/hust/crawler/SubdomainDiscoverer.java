package edu.hust.crawler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;

/** Domain boundaries and canonical URLs shared by discovery and crawling. */
public final class SubdomainDiscoverer {
    private static final Pattern EMBEDDED_URL = Pattern.compile("(?i)(?:https?:)?//[^\\s<>\"'`\\\\]+");
    public static Optional<URI> normalize(String value) {
        try {
            URI u = URI.create(value).normalize();
            String scheme = u.getScheme(), host = u.getHost();
            if (scheme == null || host == null || u.getUserInfo() != null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return Optional.empty();
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (!(host.equals("hust.edu.vn") || host.endsWith(".hust.edu.vn"))) return Optional.empty();
            int port = u.getPort();
            if (port != -1 && port != (scheme.equals("https") ? 443 : 80)) return Optional.empty();
            String path = u.getRawPath();
            String query = u.getRawQuery();
            if (query != null) {
                query = Arrays.stream(query.split("&")).filter(parameter -> {
                    String key = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    return !key.startsWith("utm_") && !Set.of("fbclid", "gclid", "mc_cid", "mc_eid", "phpsessid", "jsessionid").contains(key);
                }).reduce((a, b) -> a + "&" + b).orElse("");
            }
            return Optional.of(URI.create(scheme + "://" + host + (path == null || path.isEmpty() ? "/" : path)
                    + (query == null || query.isEmpty() ? "" : "?" + query)));
        } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    public Set<URI> links(Document document) {
        Set<URI> result = new LinkedHashSet<>();
        document.select("a[href], area[href]").forEach(a -> normalize(a.absUrl("href")).ifPresent(result::add));
        document.select("iframe[src], frame[src]").forEach(a -> normalize(a.absUrl("src")).ifPresent(result::add));
        return result;
    }

    /** Navigation URLs retain their path; asset/script references contribute a site entry point. */
    public Set<URI> entryPoints(Document document) {
        Set<URI> result = new LinkedHashSet<>(links(document));
        Set<URI> navigationOrigins = new HashSet<>();
        result.forEach(uri -> navigationOrigins.add(origin(uri)));
        for (String attribute : List.of("href", "src", "action", "data-href", "data-url")) {
            document.select("[" + attribute + "]").forEach(element ->
                    normalize(element.absUrl(attribute)).map(SubdomainDiscoverer::origin)
                            .filter(uri -> !navigationOrigins.contains(uri)).ifPresent(result::add));
        }
        // JSON and JavaScript frequently contain escaped absolute URLs outside HTML attributes.
        var matcher = EMBEDDED_URL.matcher(document.html().replace("\\/", "/"));
        while (matcher.find()) {
            try {
                String value = matcher.group();
                normalize(URI.create(document.baseUri()).resolve(value).toString())
                        .map(SubdomainDiscoverer::origin).filter(uri -> !navigationOrigins.contains(uri)).ifPresent(result::add);
            } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }

    static URI origin(URI uri) { return URI.create(uri.getScheme() + "://" + uri.getHost() + "/"); }

    public Set<String> discover(Document document) {
        Set<String> hosts = new TreeSet<>();
        entryPoints(document).forEach(u -> hosts.add(u.getHost()));
        return hosts;
    }
}
