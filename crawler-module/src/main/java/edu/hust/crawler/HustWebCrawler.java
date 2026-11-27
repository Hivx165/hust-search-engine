package edu.hust.crawler;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.zip.GZIPInputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public final class HustWebCrawler {
    private static final String AGENT = "HustResearchCrawler";
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private final HttpClient client;
    private final Map<String, RobotsRules> robots = new HashMap<>();
    private final Map<String, Long> lastRequest = new HashMap<>();
    private final Map<String, Long> hostDelays = new HashMap<>();
    private final Map<String, Integer> serverFailures = new HashMap<>();
    private final Set<String> pausedOrigins = new HashSet<>();
    private final Path storage, subdomainsOutput;
    private final ChangeDetectionService state;
    private final long delayMillis;
    private final Duration revisitInterval, roundLimit;
    private PersistentCrawlFrontier activeFrontier;

    public HustWebCrawler(Path storage, ChangeDetectionService state, long delayMillis) {
        this(storage, state, delayMillis, (Path) null);
    }
    public HustWebCrawler(Path storage, ChangeDetectionService state, long delayMillis, Path output) {
        this(storage, state, delayMillis, output, Duration.ofHours(6), Duration.ofMinutes(15));
    }
    public HustWebCrawler(Path storage, ChangeDetectionService state, long delayMillis, Path output, Duration revisit, Duration roundLimit) {
        this(storage, state, delayMillis, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build(), output, revisit, roundLimit);
    }
    HustWebCrawler(Path storage, ChangeDetectionService state, long delayMillis, HttpClient client) {
        this(storage, state, delayMillis, client, null);
    }
    HustWebCrawler(Path storage, ChangeDetectionService state, long delayMillis, HttpClient client, Path output) {
        this(storage, state, delayMillis, client, output, Duration.ZERO, Duration.ofMinutes(15));
    }
    HustWebCrawler(Path storage, ChangeDetectionService state, long delayMillis, HttpClient client, Path output, Duration revisit, Duration roundLimit) {
        if (delayMillis < 0 || revisit.isNegative() || roundLimit.isNegative() || roundLimit.isZero())
            throw new IllegalArgumentException("Invalid crawler timing");
        this.storage = storage; this.state = state; this.delayMillis = delayMillis; this.client = client;
        this.subdomainsOutput = output; this.revisitInterval = revisit; this.roundLimit = roundLimit;
    }

    public record Report(int saved, int failed, int visited, Set<String> subdomains) {}
    private record Fetch(URI uri, String type, byte[] body, boolean notModified, String etag, String modified) {}

    public Report crawl(Collection<URI> seeds, int maxDocuments, int maxVisits) throws Exception {
        if (maxDocuments < 1 || maxVisits < 1) throw new IllegalArgumentException("Limits must be positive");
        robots.clear(); pausedOrigins.clear(); hostDelays.clear();
        for (String folder : List.of("raw_files", "raw_html", "raw_feeds")) Files.createDirectories(storage.resolve(folder));
        Set<URI> visited = new HashSet<>(), stored = new HashSet<>();
        SubdomainStore domains = new SubdomainStore(storage, subdomainsOutput);
        int saved = 0, failed = 0, feeds = 0;
        long started = System.nanoTime(), lastStatus = 0;
        SubdomainDiscoverer discoverer = new SubdomainDiscoverer();
        try (PersistentCrawlFrontier frontier = new PersistentCrawlFrontier(storage.resolve("frontier.db"), revisitInterval)) {
            activeFrontier = frontier;
            frontier.addAll(domains.entryPoints()); frontier.addAll(seeds);
            frontier.addAll(state.knownUrls());
            try {
                while (saved < maxDocuments && visited.size() < maxVisits
                        && System.nanoTime() - started < roundLimit.toNanos()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    Optional<URI> next = frontier.poll(visited);
                    if (next.isEmpty()) break;
                    URI uri = next.get(); visited.add(uri);
                    if (System.nanoTime() - lastStatus > Duration.ofSeconds(5).toNanos()) {
                        writeStatus(frontier, saved, failed, visited.size(), feeds, true);
                        lastStatus = System.nanoTime();
                    }
                    try {
                        Fetch response = fetch(uri);
                        if (response == null) { frontier.failed(uri, Duration.ofHours(6)); continue; }
                        if (stored.contains(response.uri())) { frontier.succeeded(uri); continue; }
                        String type = mediaType(response.type());
                        String extension = extension(type, response.uri());
                        if (extension == null) { frontier.failed(uri, Duration.ofDays(1)); continue; }
                        String title = "";
                        if (extension.equals("html")) {
                            Document doc = Jsoup.parse(new ByteArrayInputStream(response.body()), null, response.uri().toString());
                            title = doc.title();
                            Set<URI> entries = discoverer.entryPoints(doc);
                            domains.remember(entries);
                            enqueue(entries);
                            enqueue(DiscoveryFeeds.htmlFeeds(doc));
                            enqueue(DiscoveryFeeds.defaultSitemaps(response.uri()));
                        } else if (extension.equals("xml")) {
                            DiscoveryFeeds.Feed feed = DiscoveryFeeds.parse(response.body(), response.uri());
                            domains.remember(feed.pages()); domains.remember(feed.sitemaps());
                            enqueue(feed.pages()); enqueue(feed.sitemaps());
                        }
                        String id = ChangeDetectionService.computeSHA256(response.uri().toString());
                        Path target = storage.resolve(extension.equals("html") ? "raw_html" : extension.equals("xml") ? "raw_feeds" : "raw_files")
                                .resolve(id + "." + extension);
                        if (!response.notModified() || !Files.isRegularFile(target)) writeBytes(target, response.body());
                        ChangeDetectionService.Status status = state.record(response.uri().toString(), response.body(), target, type, title);
                        state.recordHttp(response.uri().toString(), response.etag(), response.modified(), response.notModified() ? 304 : 200);
                        stored.add(response.uri());
                        domains.rememberFetched(response.uri());
                        frontier.succeeded(uri);
                        frontier.add(response.uri()); frontier.succeeded(response.uri());
                        if (extension.equals("xml")) feeds++; else saved++;
                        System.out.printf("%s [%d/%d] %s%s%n", status, saved, maxDocuments, response.uri(), response.notModified() ? " (HTTP 304)" : "");
                    } catch (InterruptedException e) {
                        frontier.release(uri); Thread.currentThread().interrupt(); throw e;
                    } catch (Exception e) {
                        failed++;
                        if (e instanceof OriginDeferredException deferred) {
                            Duration retry = Duration.between(Instant.now(), deferred.until);
                            frontier.failed(uri, retry.isNegative() ? Duration.ofSeconds(1) : retry);
                            System.err.println(uri + " : " + e.getMessage());
                            continue;
                        }
                        boolean transport = e instanceof ConnectException || e instanceof HttpConnectTimeoutException
                                || e instanceof javax.net.ssl.SSLException || e instanceof UnknownHostException;
                        if (transport) {
                            frontier.deferOrigin(uri, Instant.now().plus(Duration.ofMinutes(30)));
                            if (uri.getScheme().equals("https"))
                                SubdomainDiscoverer.normalize("http" + uri.toString().substring(5)).ifPresent(candidate -> {
                                    try { frontier.add(candidate); } catch (Exception ignored) { }
                                });
                        }
                        frontier.failed(uri, e instanceof HttpTimeoutException ? Duration.ofMinutes(30) : Duration.ofHours(6));
                        System.err.println(uri + " : " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            } finally {
                try { domains.save(); }
                finally {
                    try { state.exportManifest(storage.resolve("manifest.csv")); }
                    finally {
                        try { System.out.println("Dataset export: " + CrawlDatasetExporter.export(state, storage)); }
                        catch (Exception e) { System.err.println("Dataset export failed; raw crawl data is retained: " + e); }
                        finally { writeStatus(frontier, saved, failed, visited.size(), feeds, false); }
                    }
                }
            }
            Report report = new Report(saved, failed, visited.size(), domains.subdomains());
            Files.writeString(storage.resolve("last_run.txt"), Instant.now() + "\n" + report + "\nTotal stored URLs: " + state.count()
                    + "\nKnown frontier URLs: " + frontier.pendingCount() + "\nDue URLs: " + frontier.dueCount() + "\nFeeds processed: " + feeds + "\n");
            return report;
        } finally { activeFrontier = null; }
    }

    private void enqueue(Collection<URI> urls) throws Exception {
        activeFrontier.addAll(urls.stream().filter(uri -> !skip(uri)).toList());
    }
    private boolean skip(URI uri) {
        return uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|gif|svg|webp|css|js|mp4|mp3|zip|rar|ico|woff2?|ttf)$");
    }
    private static String mediaType(String type) { return type.toLowerCase(Locale.ROOT).split(";", 2)[0].trim(); }
    private String extension(String type, URI uri) {
        return switch (type) {
            case "text/html", "application/xhtml+xml" -> "html";
            case "text/xml", "application/xml", "application/rss+xml", "application/atom+xml" -> "xml";
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            default -> {
                String path = uri.getPath().toLowerCase(Locale.ROOT);
                if ((type.equals("application/octet-stream") || type.equals("application/gzip") || type.equals("text/plain"))
                        && (path.endsWith(".xml") || path.endsWith(".xml.gz"))) yield "xml";
                yield type.equals("application/octet-stream") && path.matches(".*\\.(pdf|docx|xlsx|doc)$")
                        ? path.substring(path.lastIndexOf('.') + 1) : null;
            }
        };
    }

    private HttpResponse<byte[]> request(URI uri, boolean conditional) throws Exception {
        String origin = SubdomainDiscoverer.origin(uri).toString();
        Optional<Instant> deferred = activeFrontier.deferredUntil(uri);
        if (deferred.isPresent()) throw new OriginDeferredException(deferred.get(), "Origin cooldown until " + deferred.get());
        long now = System.nanoTime();
        Long previous = lastRequest.get(uri.getHost());
        if (previous != null) {
            long wait = Math.max(delayMillis, hostDelays.getOrDefault(uri.getHost(), 0L)) - (now - previous) / 1_000_000L;
            if (wait > 0) Thread.sleep(wait);
        }
        lastRequest.put(uri.getHost(), System.nanoTime());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("User-Agent", AGENT + "/1.0 (HUST coursework)");
        if (conditional) {
            Optional<ChangeDetectionService.CachedPage> cached = state.cachedPage(uri.toString());
            if (cached.isPresent() && Files.isRegularFile(cached.get().path())) {
                if (cached.get().etag() != null) request.header("If-None-Match", cached.get().etag());
                if (cached.get().lastModified() != null) request.header("If-Modified-Since", cached.get().lastModified());
            }
        }
        HttpResponse<byte[]> response = client.send(request.GET().build(), info -> new LimitedBodySubscriber());
        int status = response.statusCode();
        if (status == 403 || status >= 500) serverFailures.merge(uri.getHost(), 1, Integer::sum);
        else if (status >= 200 && status < 300 || status == 304) serverFailures.remove(uri.getHost());
        if (serverFailures.getOrDefault(uri.getHost(), 0) >= 3 && status != 503) {
            Instant until = Instant.now().plus(Duration.ofHours(6));
            deferHost(uri, until);
            throw new OriginDeferredException(until, "Repeated HTTP " + status + "; host paused until " + until);
        }
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            Instant until = retryAfter(response.headers().firstValue("Retry-After").orElse(""), Duration.ofMinutes(15));
            deferHost(uri, until);
            throw new OriginDeferredException(until, "HTTP " + response.statusCode() + "; retry after " + until);
        }
        return response;
    }
    private void deferHost(URI uri, Instant until) throws Exception {
        for (String scheme : List.of("http", "https")) {
            URI origin = URI.create(scheme + "://" + uri.getHost() + "/");
            pausedOrigins.add(origin.toString()); activeFrontier.deferOrigin(origin, until);
        }
    }
    private static Instant retryAfter(String header, Duration minimum) {
        Instant floor = Instant.now().plus(minimum);
        try { return max(floor, Instant.now().plusSeconds(Math.max(0, Math.min(31_536_000L, Long.parseLong(header))))); }
        catch (NumberFormatException e) {
            try { return max(floor, ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()); }
            catch (java.time.format.DateTimeParseException ignored) { return floor; }
        }
    }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    private static final class OriginDeferredException extends IOException {
        private final Instant until;
        OriginDeferredException(Instant until, String message) { super(message); this.until = until; }
    }

    private boolean allowed(URI uri) throws Exception {
        URI originUri = SubdomainDiscoverer.origin(uri);
        String origin = originUri.toString();
        Optional<Instant> deadline = activeFrontier.deferredUntil(uri);
        if (deadline.isPresent()) throw new OriginDeferredException(deadline.get(), "Origin cooldown until " + deadline.get());
        if (pausedOrigins.contains(origin)) return false;
        RobotsRules rules = robots.get(origin);
        if (rules == null) {
            try {
                URI location = originUri.resolve("robots.txt");
                HttpResponse<byte[]> response = null;
                for (int i = 0; i <= 5; i++) {
                    response = request(location, false);
                    if (response.statusCode() < 300 || response.statusCode() >= 400) break;
                    Optional<URI> next = SubdomainDiscoverer.normalize(location.resolve(response.headers().firstValue("Location").orElse("")).toString());
                    if (next.isEmpty()) break;
                    location = next.get();
                }
                int status = response.statusCode();
                if (status == 404 || status == 410) rules = RobotsRules.parse("");
                else if (status >= 200 && status < 300) {
                    String text = new String(decode(response.body(), response.headers()), StandardCharsets.UTF_8);
                    enqueue(DiscoveryFeeds.robotsSitemaps(text, originUri));
                    rules = RobotsRules.parse(text);
                } else rules = RobotsRules.parse("User-agent: *\nDisallow: /");
                robots.put(origin, rules);
                hostDelays.merge(uri.getHost(), rules.crawlDelayMillis(), Math::max);
            } catch (InterruptedException e) { throw e; }
            catch (Exception e) { robots.put(origin, RobotsRules.parse("User-agent: *\nDisallow: /")); throw e; }
        }
        return rules.allowed(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
    }

    private Fetch fetch(URI initial) throws Exception {
        URI uri = initial;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!allowed(uri)) return null;
            HttpResponse<byte[]> response = request(uri, true);
            int status = response.statusCode();
            if (status == 304) {
                Optional<ChangeDetectionService.CachedPage> cached = state.cachedPage(uri.toString());
                if (cached.isPresent() && Files.isRegularFile(cached.get().path())) {
                    byte[] body;
                    try (InputStream input = Files.newInputStream(cached.get().path())) { body = readLimited(input); }
                    return new Fetch(uri, cached.get().contentType(), body, true,
                            response.headers().firstValue("etag").orElse(cached.get().etag()),
                            response.headers().firstValue("last-modified").orElse(cached.get().lastModified()));
                }
                response = request(uri, false); status = response.statusCode();
            }
            if (status >= 300 && status < 400) {
                Optional<URI> next = SubdomainDiscoverer.normalize(uri.resolve(response.headers().firstValue("location").orElse("")).toString());
                if (next.isEmpty()) return null;
                uri = next.get(); continue;
            }
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            String type = response.headers().firstValue("content-type").orElse("");
            if (extension(mediaType(type), uri) == null) return null;
            return new Fetch(uri, type, decode(response.body(), response.headers()), false,
                    response.headers().firstValue("etag").orElse(null), response.headers().firstValue("last-modified").orElse(null));
        }
        throw new IOException("Too many redirects");
    }

    private byte[] decode(byte[] body, HttpHeaders headers) throws IOException {
        boolean gzip = headers.firstValue("content-encoding").orElse("").toLowerCase(Locale.ROOT).contains("gzip")
                || body.length >= 2 && (body[0] & 255) == 31 && (body[1] & 255) == 139;
        if (!gzip) return body;
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) { return readLimited(input); }
    }
    private byte[] readLimited(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Response exceeds 20 MiB");
        return bytes;
    }
    private static void writeBytes(Path target, byte[] body) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "crawl-", ".tmp");
        try { Files.write(temp, body); Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
    private void writeStatus(PersistentCrawlFrontier frontier, int saved, int failed, int visited, int feeds, boolean running) throws Exception {
        Properties status = new Properties();
        status.setProperty("updated_at", Instant.now().toString());
        status.setProperty("process_id", Long.toString(ProcessHandle.current().pid()));
        status.setProperty("round_running", Boolean.toString(running));
        status.setProperty("saved_this_round", Integer.toString(saved));
        status.setProperty("failed_this_round", Integer.toString(failed));
        status.setProperty("visited_this_round", Integer.toString(visited));
        status.setProperty("feeds_this_round", Integer.toString(feeds));
        status.setProperty("known_urls", Integer.toString(frontier.pendingCount()));
        status.setProperty("due_urls", Integer.toString(frontier.dueCount()));
        status.setProperty("stored_urls", Long.toString(state.count()));
        Path temp = Files.createTempFile(storage, "status-", ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { status.store(out, "HUST crawler status"); }
            Files.move(temp, storage.resolve("crawler_status.properties"), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BYTES) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Response exceeds 20 MiB")); return;
                }
                byte[] data = new byte[buffer.remaining()]; buffer.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
