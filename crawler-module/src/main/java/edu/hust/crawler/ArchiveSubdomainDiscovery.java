package edu.hust.crawler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Incremental hostname discovery from archived public URLs; archived names are not live-site claims. */
public final class ArchiveSubdomainDiscovery {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Duration PARTIAL_REFRESH = Duration.ofHours(1);
    private static final Duration FAILURE_REFRESH = Duration.ofMinutes(30);
    private static final String COMMON_CRAWL_QUERY = "url=hust.edu.vn%2F&matchType=domain&output=json&fl=url&collapse=urlkey&pageSize=1";
    private static final String COMMON_CRAWL_QUERY_SIGNATURE = "commoncrawl-domain-v2|" + COMMON_CRAWL_QUERY;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client;
    private final URI wayback, commonCrawlCollections;
    private final int maxPages;
    private final Clock clock;
    private final Duration refresh;
    private final long requestDelayMillis;

    public record Result(SortedSet<String> hosts, boolean complete, List<String> errors,
                         Map<String, SortedSet<String>> bySource) {
        public Result {
            hosts = Collections.unmodifiableSortedSet(new TreeSet<>(hosts));
            errors = List.copyOf(errors);
            Map<String, SortedSet<String>> copy = new LinkedHashMap<>();
            bySource.forEach((source, names) -> copy.put(source, Collections.unmodifiableSortedSet(new TreeSet<>(names))));
            bySource = Collections.unmodifiableMap(copy);
        }
    }

    private record State(SortedSet<String> hosts, boolean complete, List<String> errors, long checkedAt,
                         long nextCheckAt, String endpoint, String cursor, String indexEndpoint,
                         int nextPage, int totalPages, String querySignature) {}
    private record Response(byte[] body, String retryAfter) {}
    private record WaybackPage(SortedSet<String> hosts, String cursor) {}

    public ArchiveSubdomainDiscovery() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://web.archive.org/cdx/search/cdx"),
                URI.create("https://index.commoncrawl.org/collinfo.json"), 3, Clock.systemUTC(), Duration.ofHours(24));
    }

    ArchiveSubdomainDiscovery(HttpClient client, URI wayback, URI commonCrawlCollections,
                              int maxPages, Clock clock, Duration refresh) {
        this(client, wayback, commonCrawlCollections, maxPages, clock, refresh, 1000);
    }

    ArchiveSubdomainDiscovery(HttpClient client, URI wayback, URI commonCrawlCollections,
                              int maxPages, Clock clock, Duration refresh, long requestDelayMillis) {
        if (maxPages < 1 || refresh.isNegative() || requestDelayMillis < 0)
            throw new IllegalArgumentException("Invalid archive discovery limits");
        this.client = Objects.requireNonNull(client);
        this.wayback = Objects.requireNonNull(wayback);
        this.commonCrawlCollections = Objects.requireNonNull(commonCrawlCollections);
        this.maxPages = maxPages; this.clock = Objects.requireNonNull(clock);
        this.refresh = refresh; this.requestDelayMillis = requestDelayMillis;
    }

    public Result discover(Path storage) throws IOException, InterruptedException {
        Path directory = storage.resolve("passive-discovery");
        Files.createDirectories(directory);
        Path history = directory.resolve("history").resolve(clock.millis() + "-archive-" + UUID.randomUUID());
        List<String> statuses = new ArrayList<>();
        Map<String, Long> lastRequests = new HashMap<>();
        State wb = wayback(directory, history, statuses, lastRequests);
        State cc = commonCrawl(directory, history, statuses, lastRequests);
        SortedSet<String> hosts = new TreeSet<>(wb.hosts()); hosts.addAll(cc.hosts());
        List<String> errors = new ArrayList<>(wb.errors()); errors.addAll(cc.errors());
        statuses.add(clock.instant() + " complete=" + (wb.complete() && cc.complete()) + " concrete_hosts=" + hosts.size());
        errors.forEach(error -> statuses.add("ERROR " + error));
        Files.write(directory.resolve("archive-source-status.txt"), statuses, StandardCharsets.UTF_8);
        if (Files.isDirectory(history)) Files.write(history.resolve("archive-source-status.txt"), statuses, StandardCharsets.UTF_8);
        return new Result(hosts, wb.complete() && cc.complete(), errors,
                Map.of("wayback", wb.hosts(), "commoncrawl", cc.hosts()));
    }

    private State wayback(Path directory, Path history, List<String> statuses, Map<String, Long> requests)
            throws IOException, InterruptedException {
        String source = "wayback";
        Path file = directory.resolve("wayback-state.json");
        State cached = load(file, wayback, statuses);
        if (clock.millis() < cached.nextCheckAt()) return cacheStatus(source, cached, statuses);
        SortedSet<String> hosts = new TreeSet<>(cached.hosts());
        String cursor = cached.complete() ? "" : cached.cursor();
        Set<String> seen = new HashSet<>();
        if (!cursor.isEmpty()) seen.add(cursor);
        long nextCheck = clock.instant().plus(PARTIAL_REFRESH).toEpochMilli();
        List<String> errors = new ArrayList<>();
        boolean complete = false;
        try {
            for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
                String parameters = "url=hust.edu.vn%2F&matchType=domain&fl=original&collapse=urlkey"
                        + "&filter=statuscode%3A200&output=json&showResumeKey=true&limit=5000&gzip=false"
                        + (cursor.isEmpty() ? "" : "&resumeKey=" + encode(cursor));
                Response response = request(query(wayback, parameters), source + "-page-" + pageNumber,
                        directory, history, statuses, requests);
                WaybackPage page = parseWayback(response.body());
                hosts.addAll(page.hosts());
                if (page.cursor().isEmpty()) {
                    cursor = ""; complete = true;
                    nextCheck = Math.max(clock.instant().plus(refresh).toEpochMilli(), retryAfter(response.retryAfter()).toEpochMilli());
                } else {
                    if (!seen.add(page.cursor())) throw new IOException("Repeated Wayback resume key; results are partial");
                    cursor = page.cursor();
                }
                // Save after every valid page so a restart resumes instead of replaying page one.
                save(file, state(hosts, complete, errors, nextCheck, wayback, cursor, "", 0, -1));
                if (complete) break;
                if (pageNumber == maxPages) errors.add("wayback: page budget " + maxPages + " reached; cursor saved for next run");
            }
        } catch (IOException | IllegalArgumentException e) {
            errors.add("wayback: " + describe(e));
            nextCheck = failureDeadline(e);
        }
        State result = state(hosts, complete, errors, nextCheck, wayback, cursor, "", 0, -1);
        save(file, result); sourceStatus(source, result, statuses);
        return result;
    }

    private State commonCrawl(Path directory, Path history, List<String> statuses, Map<String, Long> requests)
            throws IOException, InterruptedException {
        String source = "commoncrawl";
        Path file = directory.resolve("commoncrawl-state.json");
        State cached = load(file, commonCrawlCollections, statuses);
        if (clock.millis() < cached.nextCheckAt()) return cacheStatus(source, cached, statuses);
        SortedSet<String> hosts = new TreeSet<>(cached.hosts());
        List<String> errors = new ArrayList<>();
        String index = cached.indexEndpoint();
        int nextPage = cached.complete() ? 0 : cached.nextPage();
        int totalPages = cached.complete() ? -1 : cached.totalPages();
        long nextCheck = clock.instant().plus(PARTIAL_REFRESH).toEpochMilli();
        boolean complete = false;
        try {
            Response catalog = request(commonCrawlCollections, source + "-catalog", directory, history, statuses, requests);
            URI current = latestIndex(catalog.body());
            if (!current.toString().equals(index)) { index = current.toString(); nextPage = 0; totalPages = -1; }
            // Explicit matchType disables pywb's wildcard shorthand stripping. Keep this URL literal.
            String parameters = COMMON_CRAWL_QUERY;
            if (totalPages < 0) {
                Response metadata = request(query(current, parameters + "&showNumPages=true"), source + "-metadata",
                        directory, history, statuses, requests);
                JsonNode data = JSON.readTree(metadata.body());
                JsonNode pages = data == null ? null : data.get("pages");
                if (data == null || !data.isObject() || pages == null || !pages.isIntegralNumber()
                        || !pages.canConvertToInt() || pages.intValue() < 0) throw new IOException("Invalid Common Crawl page metadata");
                totalPages = pages.intValue();
            }
            if (nextPage > totalPages) throw new IOException("Common Crawl cursor exceeds page metadata");
            for (int batch = 0; batch < maxPages && nextPage < totalPages; batch++) {
                Response response = request(query(current, parameters + "&page=" + nextPage), source + "-page-" + nextPage,
                        directory, history, statuses, requests);
                hosts.addAll(parseCommonCrawl(response.body()));
                nextPage++;
                save(file, state(hosts, false, errors, nextCheck, commonCrawlCollections, "", index, nextPage, totalPages));
            }
            complete = nextPage == totalPages;
            if (complete) nextCheck = clock.instant().plus(refresh).toEpochMilli();
            else errors.add("commoncrawl: page budget " + maxPages + " reached; next page " + nextPage + " of " + totalPages + " saved");
        } catch (IOException | IllegalArgumentException e) {
            errors.add("commoncrawl: " + describe(e));
            nextCheck = failureDeadline(e);
        }
        State result = state(hosts, complete, errors, nextCheck, commonCrawlCollections, "", index, nextPage, totalPages);
        save(file, result); sourceStatus(source, result, statuses);
        return result;
    }

    private State state(SortedSet<String> hosts, boolean complete, List<String> errors, long nextCheck,
                        URI endpoint, String cursor, String index, int nextPage, int totalPages) {
        return new State(new TreeSet<>(hosts), complete, List.copyOf(errors), clock.millis(), nextCheck,
                endpoint.toString(), cursor, index, nextPage, totalPages,
                endpoint.equals(commonCrawlCollections) ? COMMON_CRAWL_QUERY_SIGNATURE : "");
    }

    private State cacheStatus(String source, State cached, List<String> statuses) {
        statuses.add(clock.instant() + " " + source + " CACHE next_check_at=" + Instant.ofEpochMilli(cached.nextCheckAt())
                + " complete=" + cached.complete());
        return cached;
    }

    private void sourceStatus(String source, State state, List<String> statuses) {
        statuses.add(clock.instant() + " " + source + " hosts=" + state.hosts().size() + " complete=" + state.complete()
                + " next_check_at=" + Instant.ofEpochMilli(state.nextCheckAt()));
    }

    private State load(Path file, URI endpoint, List<String> statuses) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                JsonNode data = JSON.readTree(Files.readAllBytes(file));
                if (data != null && data.isObject() && data.path("endpoint").asText().equals(endpoint.toString())) {
                    SortedSet<String> hosts = new TreeSet<>();
                    for (JsonNode name : data.path("hosts")) CertificateTransparencyDiscovery.normalizeName(name.asText()).ifPresent(hosts::add);
                    List<String> errors = new ArrayList<>();
                    for (JsonNode error : data.path("errors")) if (error.isTextual()) errors.add(error.textValue());
                    int nextPage = data.path("nextPage").asInt(0), totalPages = data.path("totalPages").asInt(-1);
                    String cursor = data.path("cursor").asText("");
                    if (nextPage < 0 || totalPages < -1 || cursor.length() > 8192) throw new IOException("Invalid saved archive cursor");
                    boolean complete = data.path("complete").asBoolean(false);
                    long nextCheckAt = data.path("nextCheckAt").asLong(0);
                    String signature = data.path("querySignature").asText("");
                    if (endpoint.equals(commonCrawlCollections) && !signature.equals(COMMON_CRAWL_QUERY_SIGNATURE)) {
                        statuses.add(clock.instant() + " commoncrawl cache query changed: reset page metadata, retain host evidence");
                        nextPage = 0; totalPages = -1; cursor = ""; complete = false;
                        // An old successful zero-result cache must not hide the corrected query for 24 hours.
                        // A source failure's future cooldown, including HTTP 429, remains binding after migration.
                        if (errors.isEmpty() || nextCheckAt <= clock.millis()) nextCheckAt = 0;
                        signature = COMMON_CRAWL_QUERY_SIGNATURE;
                    }
                    return new State(hosts, complete, errors, data.path("checkedAt").asLong(0), nextCheckAt, endpoint.toString(),
                            cursor, data.path("indexEndpoint").asText(""), nextPage, totalPages, signature);
                }
                statuses.add(clock.instant() + " " + file.getFileName() + " cache ignored: endpoint changed or invalid state");
            } catch (IOException | IllegalArgumentException e) {
                statuses.add(clock.instant() + " " + file.getFileName() + " cache unreadable: " + describe(e));
            }
        }
        return new State(new TreeSet<>(), false, List.of(), 0, 0, endpoint.toString(), "", "", 0, -1,
                endpoint.equals(commonCrawlCollections) ? COMMON_CRAWL_QUERY_SIGNATURE : "");
    }

    private static void save(Path destination, State state) throws IOException {
        Path temp = Files.createTempFile(destination.getParent(), "archive-state-", ".tmp");
        try {
            Files.write(temp, JSON.writeValueAsBytes(state));
            try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private Response request(URI uri, String label, Path directory, Path history,
                             List<String> statuses, Map<String, Long> lastRequests) throws IOException, InterruptedException {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        Long previous = lastRequests.get(origin);
        if (previous != null) {
            long elapsed = (System.nanoTime() - previous) / 1_000_000L;
            if (elapsed < requestDelayMillis) Thread.sleep(requestDelayMillis - elapsed);
        }
        lastRequests.put(origin, System.nanoTime());
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "HustResearchCrawler/1.0 (HUST coursework)").GET().build(),
                info -> new LimitedBodySubscriber());
        String retry = response.headers().firstValue("Retry-After").orElse("");
        statuses.add(clock.instant() + " " + label + " " + uri + " HTTP " + response.statusCode()
                + (retry.isEmpty() ? "" : " Retry-After=" + retry));
        Files.createDirectories(history);
        String filename = "archive-" + label + ".response";
        Files.write(history.resolve(filename), response.body());
        Path latest = directory.resolve(filename);
        if (response.statusCode() >= 200 && response.statusCode() < 300 || !Files.exists(latest)) Files.write(latest, response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new RequestFailure("HTTP " + response.statusCode() + (retry.isEmpty() ? "" : "; Retry-After=" + retry),
                    response.statusCode(), retry);
        return new Response(response.body(), retry);
    }

    private long failureDeadline(Exception error) {
        return error instanceof RequestFailure failure ? failure.retryAt : clock.instant().plus(FAILURE_REFRESH).toEpochMilli();
    }

    private Instant retryAfter(String value) {
        if (value != null && !value.isBlank()) {
            try { return clock.instant().plusSeconds(Math.max(0, Long.parseLong(value.trim()))); }
            catch (NumberFormatException | ArithmeticException | DateTimeException ignored) { }
            try { return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (DateTimeException ignored) { }
        }
        return clock.instant();
    }

    private final class RequestFailure extends IOException {
        private final long retryAt;
        RequestFailure(String message, int status, String retry) {
            super(message);
            Instant specified = retryAfter(retry), fallback = clock.instant().plus(FAILURE_REFRESH);
            retryAt = (status == 429 && specified.isAfter(clock.instant()) ? specified
                    : specified.isAfter(fallback) ? specified : fallback).toEpochMilli();
        }
    }

    private static WaybackPage parseWayback(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        int end = firstArrayEnd(text);
        JsonNode data = JSON.readTree(text.substring(0, end));
        if (!data.isArray()) throw new IOException("Expected Wayback JSON array");
        if (data.isEmpty() && text.substring(end).isBlank()) return new WaybackPage(new TreeSet<>(), "");
        if (data.isEmpty() || !data.get(0).isArray()) throw new IOException("Missing Wayback original header");
        int column = -1;
        for (int i = 0; i < data.get(0).size(); i++) if (data.get(0).get(i).asText().equals("original")) column = i;
        if (column < 0) throw new IOException("Wayback header does not contain original");
        SortedSet<String> hosts = new TreeSet<>();
        String cursor = ""; boolean footer = false;
        for (int i = 1; i < data.size(); i++) {
            JsonNode row = data.get(i);
            if (!row.isArray()) throw new IOException("Invalid Wayback row");
            if (row.isEmpty()) { footer = true; continue; }
            if (footer) {
                if (!cursor.isEmpty() || row.size() != 1 || !row.get(0).isTextual()) throw new IOException("Invalid Wayback resume footer");
                cursor = resumeKey(row.get(0).textValue());
            } else {
                if (row.size() <= column || !row.get(column).isTextual()) throw new IOException("Invalid Wayback original URL");
                hostname(row.get(column).textValue()).ifPresent(hosts::add);
            }
        }
        String tail = text.substring(end).trim();
        if (!tail.isEmpty()) {
            if (!cursor.isEmpty()) throw new IOException("Duplicate Wayback resume footer");
            if (tail.startsWith("[")) {
                JsonNode suffix = JSON.readTree(tail);
                if (!suffix.isArray() || suffix.size() != 1 || !suffix.get(0).isTextual()) throw new IOException("Invalid Wayback resume key");
                cursor = resumeKey(suffix.get(0).textValue());
            } else if (tail.startsWith("\"")) {
                JsonNode suffix = JSON.readTree(tail);
                if (!suffix.isTextual()) throw new IOException("Invalid Wayback resume key");
                cursor = resumeKey(suffix.textValue());
            } else cursor = resumeKey(tail);
        }
        return new WaybackPage(hosts, cursor);
    }

    private static int firstArrayEnd(String text) throws IOException {
        if (!text.startsWith("[")) throw new IOException("Expected Wayback JSON array");
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '[') depth++;
            else if (character == ']' && --depth == 0) return i + 1;
        }
        throw new IOException("Truncated Wayback JSON array");
    }

    private static String resumeKey(String value) throws IOException {
        String key = value.trim();
        if (key.isEmpty() || key.length() > 8192 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0)
            throw new IOException("Invalid Wayback resume key");
        return key;
    }

    private URI latestIndex(byte[] body) throws IOException {
        JsonNode catalog = JSON.readTree(body);
        if (catalog == null || !catalog.isArray()) throw new IOException("Invalid Common Crawl collection catalog");
        for (JsonNode entry : catalog) {
            JsonNode endpoint = entry.get("cdx-api");
            if (endpoint == null || !endpoint.isTextual()) continue;
            try {
                URI candidate = URI.create(endpoint.textValue());
                boolean production = "https".equalsIgnoreCase(candidate.getScheme())
                        && "index.commoncrawl.org".equalsIgnoreCase(candidate.getHost())
                        && (candidate.getPort() == -1 || candidate.getPort() == 443);
                boolean fixture = Set.of("127.0.0.1", "localhost", "::1").contains(Objects.toString(commonCrawlCollections.getHost(), ""))
                        && Objects.equals(candidate.getScheme(), commonCrawlCollections.getScheme())
                        && Objects.equals(candidate.getAuthority(), commonCrawlCollections.getAuthority());
                if ((production || fixture) && candidate.getUserInfo() == null && candidate.getFragment() == null
                        && candidate.getRawQuery() == null) return candidate;
            } catch (IllegalArgumentException ignored) { }
        }
        throw new IOException("No valid Common Crawl index endpoint in catalog");
    }

    private static SortedSet<String> parseCommonCrawl(byte[] bytes) throws IOException {
        SortedSet<String> hosts = new TreeSet<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.readTree(line);
            if (row == null || !row.isObject() || !row.path("url").isTextual()) throw new IOException("Invalid Common Crawl JSONL URL row");
            hostname(row.get("url").textValue()).ifPresent(hosts::add);
        }
        return hosts;
    }

    private static Optional<String> hostname(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                return Optional.empty();
            return CertificateTransparencyDiscovery.normalizeName(uri.getHost());
        } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static URI query(URI endpoint, String parameters) {
        return URI.create(endpoint + (endpoint.getRawQuery() == null ? "?" : "&") + parameters);
    }
    private static String describe(Exception error) {
        return error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "unknown error");
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BYTES) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Response exceeds 20 MiB")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
