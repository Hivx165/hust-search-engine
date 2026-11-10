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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Retrieves concrete certificate DNS names without treating wildcard patterns as hosts. */
public final class CertificateTransparencyDiscovery {
    private static final String DOMAIN = "hust.edu.vn";
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client;
    private final URI crtEndpoint;
    private final URI certspotterEndpoint;
    private final int maxPages;
    private final long requestDelayMillis;
    private final Clock clock;
    private final Duration refreshInterval;

    public record Result(SortedSet<String> hosts, SortedSet<String> wildcards, boolean complete, List<String> errors) {
        public Result {
            hosts = Collections.unmodifiableSortedSet(new TreeSet<>(hosts));
            wildcards = Collections.unmodifiableSortedSet(new TreeSet<>(wildcards));
            errors = List.copyOf(errors);
        }
    }

    private record Page(SortedSet<String> hosts, SortedSet<String> wildcards, int count, String cursor) {}
    private record Response(byte[] body, String retryAfter) {}
    private record SourceState(SortedSet<String> hosts, SortedSet<String> wildcards, boolean complete,
                               List<String> errors, long checkedAt, long nextCheckAt, String endpoint) {}

    public CertificateTransparencyDiscovery() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://crt.sh/"), URI.create("https://api.certspotter.com/v1/issuances"),
                20, 1000, Clock.systemUTC(), Duration.ofHours(6));
    }

    CertificateTransparencyDiscovery(HttpClient client, URI crtEndpoint, URI certspotterEndpoint,
                                     int maxPages, long requestDelayMillis) {
        this(client, crtEndpoint, certspotterEndpoint, maxPages, requestDelayMillis, Clock.systemUTC(), Duration.ZERO);
    }

    CertificateTransparencyDiscovery(HttpClient client, URI crtEndpoint, URI certspotterEndpoint,
                                     int maxPages, long requestDelayMillis, Clock clock, Duration refreshInterval) {
        if (maxPages < 1 || requestDelayMillis < 0) throw new IllegalArgumentException("Invalid discovery limits");
        if (refreshInterval.isNegative()) throw new IllegalArgumentException("Negative refresh interval");
        this.client = Objects.requireNonNull(client);
        this.crtEndpoint = Objects.requireNonNull(crtEndpoint);
        this.certspotterEndpoint = Objects.requireNonNull(certspotterEndpoint);
        this.maxPages = maxPages;
        this.requestDelayMillis = requestDelayMillis;
        this.clock = Objects.requireNonNull(clock);
        this.refreshInterval = Objects.requireNonNull(refreshInterval);
    }

    public Result discover(Path storage) throws IOException, InterruptedException {
        Path directory = storage.resolve("passive-discovery");
        Files.createDirectories(directory);
        List<String> statuses = new ArrayList<>();
        Map<String, Long> lastRequests = new HashMap<>();
        Path history = directory.resolve("history").resolve(clock.millis() + "-" + UUID.randomUUID());
        SourceState crt = collectSource("crtsh", crtEndpoint, false, directory, history, statuses, lastRequests);
        SourceState certspotter = collectSource("certspotter", certspotterEndpoint, true, directory, history, statuses, lastRequests);
        SortedSet<String> hosts = new TreeSet<>(crt.hosts()), wildcards = new TreeSet<>(crt.wildcards());
        hosts.addAll(certspotter.hosts()); wildcards.addAll(certspotter.wildcards());
        List<String> errors = new ArrayList<>(crt.errors()); errors.addAll(certspotter.errors());
        boolean complete = crt.complete() && certspotter.complete();
        statuses.add("Collected at " + clock.instant() + ": concrete_hosts=" + hosts.size()
                + ", wildcard_patterns=" + wildcards.size() + ", complete=" + complete);
        errors.forEach(error -> statuses.add("ERROR " + error));
        Files.write(directory.resolve("source-status.txt"), statuses, StandardCharsets.UTF_8);
        if (Files.isDirectory(history)) Files.write(history.resolve("source-status.txt"), statuses, StandardCharsets.UTF_8);
        return new Result(hosts, wildcards, complete, errors);
    }

    private SourceState collectSource(String source, URI endpoint, boolean certspotter, Path directory, Path history,
                                      List<String> statuses, Map<String, Long> lastRequests) throws IOException, InterruptedException {
        Path stateFile = directory.resolve(source + "-state.json");
        SourceState cached = loadState(stateFile, endpoint, statuses);
        if (clock.millis() < cached.nextCheckAt()) {
            statuses.add(clock.instant() + " " + source + " CACHE checked_at=" + Instant.ofEpochMilli(cached.checkedAt())
                    + " next_check_at=" + Instant.ofEpochMilli(cached.nextCheckAt()) + " complete=" + cached.complete());
            return cached;
        }
        SortedSet<String> hosts = new TreeSet<>(cached.hosts()), wildcards = new TreeSet<>(cached.wildcards());
        List<String> errors = new ArrayList<>();
        boolean complete = false;
        long nextCheck = clock.instant().plus(refreshInterval).toEpochMilli();
        String cursor = "";
        Set<String> seenCursors = new HashSet<>();
        try {
            for (int pageNumber = 1; pageNumber <= (certspotter ? maxPages : 1); pageNumber++) {
                String parameters = certspotter ? "domain=hust.edu.vn&include_subdomains=true&expand=dns_names"
                        + (cursor.isEmpty() ? "" : "&after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8))
                        : "q=%25.hust.edu.vn&output=json";
                Response response = request(query(endpoint, parameters), source, pageNumber, directory, history, statuses, lastRequests);
                Page page = parse(response.body(), certspotter);
                hosts.addAll(page.hosts()); wildcards.addAll(page.wildcards());
                if (!certspotter || page.count() == 0) {
                    complete = true;
                    nextCheck = Math.max(nextCheck, retryAfter(response.retryAfter(), clock.instant()).toEpochMilli());
                    break;
                }
                if (page.cursor().isBlank() || !seenCursors.add(page.cursor()))
                    throw new IOException("Missing or repeated pagination cursor; results are partial");
                cursor = page.cursor();
                if (pageNumber == maxPages) throw new IOException("page limit " + maxPages + " reached; results are partial");
            }
        } catch (IOException | IllegalArgumentException e) {
            errors.add(source + ": " + describe(e));
            Instant deadline = e instanceof RequestFailure failure ? failure.retryAt
                    : clock.instant().plus(Duration.ofMinutes(15));
            nextCheck = deadline.toEpochMilli();
        }
        SourceState result = new SourceState(hosts, wildcards, complete, errors, clock.millis(), nextCheck, endpoint.toString());
        saveState(stateFile, result);
        statuses.add(clock.instant() + " " + source + " next_check_at=" + Instant.ofEpochMilli(nextCheck));
        return result;
    }

    private SourceState loadState(Path file, URI endpoint, List<String> statuses) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                JsonNode state = JSON.readTree(Files.readAllBytes(file));
                if (state != null && state.isObject() && state.path("endpoint").asText().equals(endpoint.toString())) {
                    SortedSet<String> hosts = new TreeSet<>(), wildcards = new TreeSet<>();
                    for (JsonNode host : state.path("hosts")) normalizeName(host.asText()).ifPresent(hosts::add);
                    for (JsonNode wildcard : state.path("wildcards")) collect(wildcard.asText(), new TreeSet<>(), wildcards);
                    List<String> errors = new ArrayList<>();
                    for (JsonNode error : state.path("errors")) if (error.isTextual()) errors.add(error.textValue());
                    return new SourceState(hosts, wildcards, state.path("complete").asBoolean(false), errors,
                            state.path("checkedAt").asLong(0), state.path("nextCheckAt").asLong(0), endpoint.toString());
                }
                statuses.add(clock.instant() + " " + file.getFileName() + " cache ignored: endpoint changed or invalid state");
            } catch (IOException | IllegalArgumentException e) {
                statuses.add(clock.instant() + " " + file.getFileName() + " cache unreadable: " + describe(e));
            }
        }
        return new SourceState(new TreeSet<>(), new TreeSet<>(), false, List.of(), 0, 0, endpoint.toString());
    }

    private static void saveState(Path destination, SourceState state) throws IOException {
        Path temp = Files.createTempFile(destination.getParent(), "ct-state-", ".tmp");
        try {
            Files.write(temp, JSON.writeValueAsBytes(state));
            try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private Response request(URI uri, String source, int page, Path directory, Path history, List<String> statuses,
                           Map<String, Long> lastRequests) throws IOException, InterruptedException {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        Long previous = lastRequests.get(origin);
        if (previous != null) {
            long elapsed = (System.nanoTime() - previous) / 1_000_000L;
            if (elapsed < requestDelayMillis) Thread.sleep(requestDelayMillis - elapsed);
        }
        lastRequests.put(origin, System.nanoTime());
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                        .header("User-Agent", "HustResearchCrawler/1.0 (HUST coursework)").GET().build(),
                info -> new LimitedBodySubscriber());
        String retry = response.headers().firstValue("Retry-After").orElse("");
        statuses.add(clock.instant() + " " + source + " page=" + page + " " + uri + " HTTP " + response.statusCode()
                + (retry.isEmpty() ? "" : " Retry-After=" + retry));
        String filename = source + "-page-" + String.format(Locale.ROOT, "%03d", page) + ".response";
        Files.createDirectories(history);
        Files.write(history.resolve(filename), response.body());
        Path latest = directory.resolve(filename);
        // Failed requests remain in history and cannot overwrite an earlier successful raw response.
        if (response.statusCode() >= 200 && response.statusCode() < 300 || !Files.exists(latest)) Files.write(latest, response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new RequestFailure("HTTP " + response.statusCode() + (retry.isEmpty() ? "" : "; Retry-After=" + retry)
                    + "; stopped without retrying", response.statusCode(), retry, clock.instant());
        return new Response(response.body(), retry);
    }

    private static Instant retryAfter(String value, Instant now) {
        if (value != null && !value.isBlank()) {
            try { return now.plusSeconds(Math.max(0, Long.parseLong(value.trim()))); }
            catch (NumberFormatException | ArithmeticException | java.time.DateTimeException ignored) { }
            try { return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (DateTimeParseException ignored) { }
        }
        return now;
    }

    private static final class RequestFailure extends IOException {
        private final Instant retryAt;
        RequestFailure(String message, int status, String retryAfter, Instant now) {
            super(message);
            Instant specified = retryAfter(retryAfter, now);
            Instant minimum = now.plus(status == 429 ? Duration.ofHours(1) : Duration.ofMinutes(15));
            retryAt = status == 429 && specified.isAfter(now) ? specified : specified.isAfter(minimum) ? specified : minimum;
        }
    }

    private static Page parse(byte[] bytes, boolean certspotter) throws IOException {
        JsonNode data = JSON.readTree(bytes);
        if (data == null || !data.isArray()) throw new IOException("Expected a JSON array");
        SortedSet<String> hosts = new TreeSet<>(), wildcards = new TreeSet<>();
        String cursor = "";
        for (JsonNode item : data) {
            if (!item.isObject()) throw new IOException("Expected certificate objects in JSON array");
            if (certspotter) {
                JsonNode names = item.get("dns_names");
                if (names == null || !names.isArray()) throw new IOException("Missing expanded dns_names array");
                for (JsonNode name : names) {
                    if (!name.isTextual()) throw new IOException("Invalid dns_names entry");
                    collect(name.textValue(), hosts, wildcards);
                }
                JsonNode id = item.get("id");
                cursor = id == null || !id.isTextual() ? "" : id.textValue();
            } else {
                JsonNode names = item.get("name_value"), commonName = item.get("common_name");
                if ((names == null || !names.isTextual()) && (commonName == null || !commonName.isTextual()))
                    throw new IOException("Missing certificate DNS name fields");
                if (names != null && names.isTextual()) collect(names.textValue(), hosts, wildcards);
                if (commonName != null && commonName.isTextual()) collect(commonName.textValue(), hosts, wildcards);
            }
        }
        return new Page(hosts, wildcards, data.size(), cursor);
    }

    private static void collect(String value, Set<String> hosts, Set<String> wildcards) {
        for (String line : value.split("\\R")) {
            String name = line.trim().toLowerCase(Locale.ROOT);
            if (name.startsWith("*.")) {
                String base = name.substring(2);
                if (base.endsWith(".")) base = base.substring(0, base.length() - 1);
                if (validHost(base)) wildcards.add("*." + base);
            } else normalizeName(name).ifPresent(hosts::add);
        }
    }

    /** Concrete HUST subdomains only; useful when validating cached candidates too. */
    static Optional<String> normalizeName(String name) {
        if (name == null) return Optional.empty();
        name = name.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return validHost(name) && !name.equals(DOMAIN) ? Optional.of(name) : Optional.empty();
    }

    private static boolean validHost(String name) {
        if (name.length() > 253 || !(name.equals(DOMAIN) || name.endsWith("." + DOMAIN))) return false;
        for (String label : name.split("\\.", -1))
            if (label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        return true;
    }

    private static URI query(URI endpoint, String parameters) {
        return URI.create(endpoint + (endpoint.getRawQuery() == null ? "?" : "&") + parameters);
    }

    private static String describe(Exception error) {
        return error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "unknown error");
    }

    /** Bounded byte subscriber keeps the request timeout active until its full body arrives. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Response exceeds 20 MiB"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
