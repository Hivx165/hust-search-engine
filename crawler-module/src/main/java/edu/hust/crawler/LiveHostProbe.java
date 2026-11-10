package edu.hust.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import javax.net.ssl.SSLException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** A bounded HTTP inventory; DNS evidence alone is never reported as HTTP availability. */
public final class LiveHostProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 64 * 1024;
    private static final String AGENT = "HustResearchCrawler/1.0 (HUST coursework)";
    private final HttpClient client;
    private final Clock clock;
    private final Duration ttl;
    private final int maxNewChecks;
    private final long delayMs;
    private final String initialScheme;
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequests = new ConcurrentHashMap<>();
    private final Map<String, RobotsPolicy> robots = new ConcurrentHashMap<>();
    private final Map<String, Long> hostCooldowns = new ConcurrentHashMap<>();
    private PersistentCrawlFrontier frontier;

    public record Row(String host, String ip, int httpStatus, String finalUrl, String title,
                      String status, String checkedAt) {}
    private record Cached(Row row, long nextEligibleAt) {}
    private record Reply(int code, String contentType, String retryAfter, String location, byte[] body) {}
    private record Outcome(URI uri, Reply reply, String blocked) {}
    private record RobotsDecision(boolean allowed, String status, Instant retryAt) {}
    private record RobotsPolicy(RobotsRules rules, boolean allowAll, String status, Instant retryAt) {}

    public LiveHostProbe() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                Clock.systemUTC(), Duration.ofHours(6), 24, 1000);
    }

    LiveHostProbe(HttpClient client, Clock clock, Duration ttl, int maxNewChecks, long delayMs) {
        this(client, clock, ttl, maxNewChecks, delayMs, "https");
    }

    // HTTP is injectable only for deterministic proxy fixtures; production begins with HTTPS.
    LiveHostProbe(HttpClient client, Clock clock, Duration ttl, int maxNewChecks, long delayMs, String initialScheme) {
        if (ttl.isNegative() || maxNewChecks < 0 || delayMs < 0 || !Set.of("http", "https").contains(initialScheme))
            throw new IllegalArgumentException("Invalid live probe limits");
        if (client.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Live probe client must not follow redirects automatically");
        this.client = Objects.requireNonNull(client); this.clock = Objects.requireNonNull(clock);
        this.ttl = ttl; this.maxNewChecks = maxNewChecks; this.delayMs = delayMs; this.initialScheme = initialScheme;
    }

    /** Cached rows remain explicit; unchecked due hosts wait their turn rather than being called live. */
    public synchronized List<Row> probe(Collection<DnsSubdomainVerifier.Entry> dns, Path storage)
            throws IOException, InterruptedException {
        try { frontier = new PersistentCrawlFrontier(storage.resolve("frontier.db"), Duration.ofHours(1), clock); }
        catch (Exception e) { throw new IOException("Cannot open shared crawl cooldowns", e); }
        try { return probeRows(dns, storage); }
        finally {
            try { frontier.close(); } catch (SQLException e) { throw new IOException("Cannot close shared crawl cooldowns", e); }
            finally { frontier = null; }
        }
    }

    private List<Row> probeRows(Collection<DnsSubdomainVerifier.Entry> dns, Path storage)
            throws IOException, InterruptedException {
        Path directory = storage.resolve("discovery");
        Files.createDirectories(directory);
        Path stateFile = directory.resolve("live-hosts-state.json");
        hostCooldowns.clear();
        Map<String, Cached> cache = load(stateFile);
        Map<String, DnsSubdomainVerifier.Entry> inputs = new TreeMap<>();
        for (DnsSubdomainVerifier.Entry entry : dns) normalize(entry.hostname()).ifPresent(host -> inputs.put(host, entry));
        List<String> due = new ArrayList<>();
        for (var input : inputs.entrySet()) {
            String host = input.getKey(); var entry = input.getValue();
            if (!entry.status().equals("RESOLVED") || entry.addresses().isEmpty()) {
                cache.put(host, new Cached(new Row(host, "", 0, "", "", "DNS_UNRESOLVED", clock.instant().toString()), 0));
            } else {
                Cached previous = cache.get(host);
                if (previous == null) cache.put(host, new Cached(new Row(host, ip(entry), 0, "", "", "NOT_CHECKED_YET", ""), 0));
                if (previous == null || previous.nextEligibleAt() <= clock.millis()) due.add(host);
            }
        }
        due.sort(Comparator.comparing((String host) -> cache.get(host).row().checkedAt()).thenComparing(host -> host));
        robots.clear();
        int count = Math.min(maxNewChecks, due.size());
        if (count == 0) { save(stateFile, directory, cache); return rows(cache); }
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(4, count), runnable -> {
            Thread thread = new Thread(runnable, "hust-live-probe"); thread.setDaemon(true); return thread;
        });
        CompletionService<Cached> completed = new ExecutorCompletionService<>(workers);
        List<Future<Cached>> tasks = new ArrayList<>();
        try {
            for (String host : due.subList(0, count)) tasks.add(completed.submit(() -> check(host, inputs.get(host))));
            for (int i = 0; i < count; i++) {
                try {
                    Cached result = completed.take().get();
                    cache.put(result.row().host(), result);
                    // A stopped process retains completed host observations.
                    writeAtomic(stateFile, stateBytes(cache));
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof InterruptedException interrupted) throw interrupted;
                    throw new IOException("Live host worker failed", e.getCause());
                }
            }
        } finally {
            tasks.forEach(task -> task.cancel(true)); workers.shutdownNow();
            try { workers.awaitTermination(5, TimeUnit.SECONDS); }
            finally { save(stateFile, directory, cache); }
        }
        return rows(cache);
    }

    private Cached check(String host, DnsSubdomainVerifier.Entry dns) throws InterruptedException {
        URI start = URI.create(initialScheme + "://" + host + "/");
        try {
            Outcome outcome;
            try { outcome = follow(start, "HEAD", true); }
            catch (IOException e) {
                if (!initialScheme.equals("https") || !connectFailure(e)) throw e;
                start = URI.create("http://" + host + "/");
                outcome = follow(start, "HEAD", true);
            }
            if (!outcome.blocked().isEmpty()) return observed(host, dns, outcome, "", outcome.blocked());
            if (outcome.reply().code() == 405) {
                RobotsDecision permission = robotsAllowed(outcome.uri());
                if (!permission.allowed()) {
                    Row row = new Row(host, ip(dns), 405, outcome.uri().toString(), "", permission.status(), clock.instant().toString());
                    return new Cached(row, Math.max(clock.instant().plus(ttl).toEpochMilli(), permission.retryAt().toEpochMilli()));
                }
                try { outcome = follow(outcome.uri(), "GET", true); }
                catch (RobotsBlocked e) {
                    Row row = new Row(host, ip(dns), 405, e.uri.toString(), "", e.decision.status(), clock.instant().toString());
                    return new Cached(row, Math.max(clock.instant().plus(ttl).toEpochMilli(), e.decision.retryAt().toEpochMilli()));
                }
            }
            String title = "";
            if (outcome.reply().code() >= 200 && outcome.reply().code() < 300
                    && outcome.reply().contentType().toLowerCase(Locale.ROOT).contains("html") && outcome.reply().body().length > 0)
                title = Jsoup.parse(new ByteArrayInputStream(outcome.reply().body()), null, outcome.uri().toString()).title();
            int code = outcome.reply().code();
            String status = !outcome.blocked().isEmpty() ? outcome.blocked()
                    : code >= 200 && code < 300 ? "HTTP_2XX" : "HTTP_" + code;
            return observed(host, dns, outcome, title, status);
        } catch (RobotsBlocked e) {
            return new Cached(new Row(host, ip(dns), 0, e.uri.toString(), "", e.decision.status(), clock.instant().toString()),
                    Math.max(clock.instant().plus(ttl).toEpochMilli(), e.decision.retryAt().toEpochMilli()));
        } catch (CoolingDown e) {
            return new Cached(new Row(host, ip(dns), 0, e.uri.toString(), "", e.status, clock.instant().toString()),
                    Math.max(clock.instant().plus(ttl).toEpochMilli(), e.until));
        } catch (IOException | IllegalArgumentException e) {
            Row row = new Row(host, ip(dns), 0, start.toString(), "", "NETWORK_ERROR:" + e.getClass().getSimpleName(), clock.instant().toString());
            return new Cached(row, clock.instant().plus(ttl).toEpochMilli());
        }
    }

    private Cached observed(String host, DnsSubdomainVerifier.Entry dns, Outcome outcome, String title, String status) {
        Instant next = clock.instant().plus(ttl);
        if (outcome.reply().code() == 429) next = later(next, retryAfter(outcome.reply().retryAfter(), clock.instant().plus(Duration.ofMinutes(15))));
        else next = later(next, retryAfter(outcome.reply().retryAfter(), clock.instant()));
        return new Cached(new Row(host, ip(dns), outcome.reply().code(), outcome.uri().toString(), title, status,
                clock.instant().toString()), next.toEpochMilli());
    }

    private Outcome follow(URI start, String method, boolean checkRobots) throws IOException, InterruptedException {
        URI uri = start;
        for (int redirects = 0; ; redirects++) {
            if (checkRobots) {
                RobotsDecision decision = robotsAllowed(uri);
                if (!decision.allowed()) throw new RobotsBlocked(uri, decision);
            }
            Reply response = request(uri, method);
            if (response.code() < 300 || response.code() >= 400) return new Outcome(uri, response, "");
            if (response.location().isBlank()) return new Outcome(uri, response, "REDIRECT_WITHOUT_LOCATION");
            if (redirects >= 5) return new Outcome(uri, response, "REDIRECT_LIMIT");
            Optional<URI> next;
            try { next = SubdomainDiscoverer.normalize(uri.resolve(response.location()).toString()); }
            catch (IllegalArgumentException e) { next = Optional.empty(); }
            if (next.isEmpty()) return new Outcome(uri, response, "REDIRECT_BLOCKED");
            uri = next.get();
        }
    }

    private RobotsDecision robotsAllowed(URI uri) throws IOException, InterruptedException {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        RobotsPolicy policy = robots.get(origin);
        if (policy == null) {
            Outcome response = follow(URI.create(origin + "/robots.txt"), "GET", false);
            int code = response.reply().code();
            Instant retry = retryAfter(response.reply().retryAfter(), clock.instant());
            if (code == 429) retry = later(retry, clock.instant().plus(Duration.ofMinutes(15)));
            if (!response.blocked().isEmpty()) policy = new RobotsPolicy(null, false, "ROBOTS_REDIRECT_BLOCKED", retry);
            else if (code == 404 || code == 410) policy = new RobotsPolicy(null, true, "", retry);
            else if (code >= 200 && code < 300) {
                RobotsRules rules = RobotsRules.parse(new String(response.reply().body(), StandardCharsets.UTF_8));
                policy = new RobotsPolicy(rules, false, "ROBOTS_DENIED", retry);
            } else policy = new RobotsPolicy(null, false, "ROBOTS_HTTP_" + code, retry);
            robots.put(origin, policy);
        }
        String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return new RobotsDecision(policy.rules() != null ? policy.rules().allowed(path) : policy.allowAll(), policy.status(), policy.retryAt());
    }

    private Reply request(URI uri, String method) throws IOException, InterruptedException {
        Object lock = hostLocks.computeIfAbsent(uri.getHost(), ignored -> new Object());
        synchronized (lock) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                Optional<Instant> deferred = frontier.deferredUntil(uri);
                if (deferred.isPresent()) throw new CoolingDown(uri, deferred.get().toEpochMilli(), "ORIGIN_COOLDOWN");
            } catch (SQLException e) { throw new IOException("Cannot read shared crawl cooldown", e); }
            long cooldown = hostCooldowns.getOrDefault(uri.getHost(), 0L);
            if (cooldown > clock.millis()) throw new CoolingDown(uri, cooldown, "HOST_COOLDOWN");
            long previous = lastRequests.getOrDefault(uri.getHost(), 0L);
            long elapsed = (System.nanoTime() - previous) / 1_000_000L;
            long effectiveDelay = delayMs;
            for (var policy : robots.entrySet())
                if (URI.create(policy.getKey()).getHost().equals(uri.getHost()) && policy.getValue().rules() != null)
                    effectiveDelay = Math.max(effectiveDelay, policy.getValue().rules().crawlDelayMillis());
            if (previous != 0 && elapsed < effectiveDelay) Thread.sleep(effectiveDelay - elapsed);
            lastRequests.put(uri.getHost(), System.nanoTime());
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                            .header("User-Agent", AGENT).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    ignored -> new LimitedBody());
            if (response.statusCode() == 429 || response.statusCode() == 503) {
                Instant until = later(clock.instant().plus(ttl), retryAfter(response.headers().firstValue("Retry-After").orElse(""),
                        clock.instant().plus(Duration.ofMinutes(15))));
                hostCooldowns.merge(uri.getHost(), until.toEpochMilli(), Math::max);
                try {
                    frontier.deferOrigin(URI.create("http://" + uri.getHost() + "/"), until);
                    frontier.deferOrigin(URI.create("https://" + uri.getHost() + "/"), until);
                }
                catch (SQLException e) { throw new IOException("Cannot store shared crawl cooldown", e); }
            }
            return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("Retry-After").orElse(""), response.headers().firstValue("Location").orElse(""), response.body());
        }
    }

    private static boolean connectFailure(Throwable error) {
        for (int i = 0; error != null && i < 10; i++, error = error.getCause())
            if (error instanceof SSLException || error instanceof ConnectException || error instanceof HttpConnectTimeoutException
                    || error instanceof UnknownHostException || error instanceof java.nio.channels.UnresolvedAddressException) return true;
        return false;
    }

    private static Optional<String> normalize(String host) {
        if (host == null) return Optional.empty();
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        String normalized = value;
        return SubdomainDiscoverer.normalize("https://" + value + "/").filter(uri -> normalized.equals(uri.getHost())).map(URI::getHost);
    }
    private static String ip(DnsSubdomainVerifier.Entry entry) { return String.join(";", new TreeSet<>(entry.addresses())); }
    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
    private Instant retryAfter(String header, Instant fallback) {
        if (header != null && !header.isBlank()) {
            try { return clock.instant().plusSeconds(Math.max(0, Long.parseLong(header.trim()))); }
            catch (NumberFormatException | ArithmeticException | DateTimeException ignored) { }
            try { return ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (DateTimeParseException ignored) { }
        }
        return fallback;
    }

    private Map<String, Cached> load(Path stateFile) throws IOException {
        Map<String, Cached> result = new TreeMap<>();
        if (!Files.exists(stateFile)) return result;
        JsonNode data = JSON.readTree(Files.readAllBytes(stateFile));
        if (data == null || !(data.isArray() || data.isObject())) throw new IOException("Invalid live host cache");
        JsonNode storedRows = data.isArray() ? data : data.path("rows");
        if (!storedRows.isArray()) throw new IOException("Invalid live host cache rows");
        if (data.isObject()) data.path("cooldowns").fields().forEachRemaining(entry ->
                normalize(entry.getKey()).ifPresent(host -> hostCooldowns.put(host, entry.getValue().asLong(0))));
        for (JsonNode item : storedRows) {
            JsonNode row = item.path("row");
            Optional<String> host = normalize(row.path("host").asText());
            if (host.isEmpty()) continue;
            Row value = new Row(host.get(), row.path("ip").asText(), row.path("httpStatus").asInt(0), row.path("finalUrl").asText(),
                    row.path("title").asText(), row.path("status").asText("NOT_CHECKED_YET"), row.path("checkedAt").asText());
            result.put(host.get(), new Cached(value, item.path("nextEligibleAt").asLong(0)));
        }
        return result;
    }
    private static List<Row> rows(Map<String, Cached> cache) {
        return cache.values().stream().map(Cached::row).sorted(Comparator.comparing(Row::host)).toList();
    }
    private void save(Path stateFile, Path directory, Map<String, Cached> cache) throws IOException {
        writeAtomic(stateFile, stateBytes(cache));
        StringBuilder csv = new StringBuilder("host,ip,http_status,final_url,title,status,checked_at\n");
        for (Row row : rows(cache)) {
            csv.append(quote(row.host())).append(',').append(quote(row.ip())).append(',').append(row.httpStatus()).append(',')
                    .append(quote(row.finalUrl())).append(',').append(quote(row.title())).append(',').append(quote(row.status())).append(',')
                    .append(quote(row.checkedAt())).append('\n');
        }
        writeAtomic(directory.resolve("live-hosts.csv"), csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    private byte[] stateBytes(Map<String, Cached> cache) throws IOException {
        List<Cached> values = cache.values().stream().sorted(Comparator.comparing(value -> value.row().host())).toList();
        return JSON.writeValueAsBytes(Map.of("rows", values, "cooldowns", new TreeMap<>(hostCooldowns)));
    }
    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static void writeAtomic(Path file, byte[] content) throws IOException {
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "live-probe-", ".tmp");
        try {
            Files.write(temp, content);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static final class RobotsBlocked extends IOException {
        private final URI uri; private final RobotsDecision decision;
        RobotsBlocked(URI uri, RobotsDecision decision) { this.uri = uri; this.decision = decision; }
    }
    private static final class CoolingDown extends IOException {
        private final URI uri; private final long until; private final String status;
        CoolingDown(URI uri, long until, String status) { this.uri = uri; this.until = until; this.status = status; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_BODY) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Live probe body exceeds 64 KiB")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
