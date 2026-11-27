package edu.hust.crawler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Small, bounded DNS hypothesis expansion; a guessed label is never evidence of a discovered host. */
public final class WordlistSubdomainDiscovery {
    private static final String ROOT = "hust.edu.vn";
    private static final int MAX_CANDIDATES = 5000, MAX_ZONES = 100, MAX_INPUT_BYTES = 20 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DnsSubdomainVerifier verifier;
    private final Clock clock;
    private final Duration ttl;
    private final byte[] builtIn;

    public record Result(SortedSet<String> hosts, SortedSet<String> generatedCandidates,
                         Set<String> wildcardMatches, List<String> errors, boolean complete,
                         Map<String, SortedSet<String>> bySource) {
        public Result {
            hosts = Collections.unmodifiableSortedSet(new TreeSet<>(hosts));
            generatedCandidates = Collections.unmodifiableSortedSet(new TreeSet<>(generatedCandidates));
            wildcardMatches = Collections.unmodifiableSet(new TreeSet<>(wildcardMatches));
            errors = List.copyOf(errors);
            Map<String, SortedSet<String>> copied = new TreeMap<>();
            bySource.forEach((source, names) -> copied.put(source, Collections.unmodifiableSortedSet(new TreeSet<>(names))));
            bySource = Collections.unmodifiableMap(copied);
        }
    }

    private record Cache(String fingerprint, long checkedAt, long nextCheckAt, Result result) {}
    private record Input(String source, byte[] bytes, boolean present) {
        String text() {
            String value = new String(bytes, StandardCharsets.UTF_8);
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        }
        String hash() { return ChangeDetectionService.computeSHA256(bytes); }
    }
    private record Parsed(SortedSet<String> hosts, boolean truncated) {}

    public WordlistSubdomainDiscovery() {
        this(new DnsSubdomainVerifier(), Clock.systemUTC(), Duration.ofDays(1));
    }

    WordlistSubdomainDiscovery(DnsSubdomainVerifier.Resolver resolver, Clock clock, Duration ttl) {
        this(new DnsSubdomainVerifier(resolver, 8), clock, ttl);
    }

    private WordlistSubdomainDiscovery(DnsSubdomainVerifier verifier, Clock clock, Duration ttl) {
        this.verifier = Objects.requireNonNull(verifier);
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        if (ttl.isNegative()) throw new IllegalArgumentException("Negative wordlist refresh interval");
        try (InputStream input = WordlistSubdomainDiscovery.class.getResourceAsStream("/subdomain_wordlist.txt")) {
            if (input == null) throw new IOException("Built-in subdomain wordlist is missing");
            builtIn = readLimited(input);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** External names are imported evidence, not claimed DNS successes; the runner verifies them again. */
    public Result discover(Path storage, Path optionalWordlist, Path externalFile) throws IOException, InterruptedException {
        return discover(storage, optionalWordlist, externalFile, true);
    }

    public Result discover(Path storage, Path optionalWordlist, Path externalFile, boolean wordlistEnabled)
            throws IOException, InterruptedException {
        Input words = !wordlistEnabled ? new Input("wordlist:disabled", new byte[0], false)
                : optionalWordlist == null ? new Input("resource:/subdomain_wordlist.txt", builtIn, true) : input(optionalWordlist, false);
        Input external = externalFile == null ? new Input("external-file:disabled", new byte[0], false)
                : input(externalFile, true);
        String fingerprint = ChangeDetectionService.computeSHA256("wordlist-discovery-v1\n" + ttl + "\n" + words.source() + "\n"
                + words.hash() + "\n" + external.source() + "\n" + external.present() + "\n" + external.hash());
        Path directory = storage.resolve("wordlist-discovery");
        Files.createDirectories(directory);
        List<String> errors = new ArrayList<>();
        Cache cached = readCache(directory.resolve("cache.json"), fingerprint, errors);
        if (cached != null && cached.checkedAt() <= clock.millis() && clock.millis() < cached.nextCheckAt())
            return cached.result();

        Parsed imported = parse(external, MAX_CANDIDATES, false, errors);
        Parsed guesses = parse(words, MAX_CANDIDATES - imported.hosts().size(), true, errors);
        SortedSet<String> generated = guesses.hosts(), externalNames = imported.hosts();
        boolean complete = !imported.truncated() && !guesses.truncated();
        Map<String, List<String>> controls = new TreeMap<>();
        Set<String> eligible = new TreeSet<>();
        int uncontrolled = 0;
        for (String candidate : generated) {
            String zone = candidate.substring(candidate.indexOf('.') + 1);
            if (!controls.containsKey(zone)) {
                // A sufficiently unpredictable random label must fit inside the DNS name limit.
                if (controls.size() >= MAX_ZONES || zone.length() + 1 + 47 > 253) { uncontrolled++; continue; }
                controls.put(zone, List.of(control(zone), control(zone)));
            }
            eligible.add(candidate);
        }
        if (uncontrolled != 0) {
            complete = false;
            errors.add("Wildcard-control zone/length budget reached; " + uncontrolled + " guesses were not DNS checked or promoted");
        }
        Set<String> queries = new TreeSet<>(eligible);
        controls.values().forEach(queries::addAll);
        if (!eligible.isEmpty()) queries.add(ROOT); // Positive baseline makes an all-unresolved outage visible.
        Map<String, DnsSubdomainVerifier.Entry> evidence = new TreeMap<>();
        for (var entry : verifier.verify(queries)) evidence.put(entry.hostname(), entry);

        SortedSet<String> resolved = new TreeSet<>(), wildcardMatches = new TreeSet<>();
        for (String candidate : eligible) {
            var answer = evidence.get(candidate);
            if (answer == null || !answer.status().equals("RESOLVED")) continue;
            String zone = candidate.substring(candidate.indexOf('.') + 1);
            Set<String> wildcardAddresses = new HashSet<>();
            for (String name : controls.get(zone)) {
                var sample = evidence.get(name);
                if (sample != null) wildcardAddresses.addAll(sample.addresses());
            }
            if (!Collections.disjoint(answer.addresses(), wildcardAddresses)) wildcardMatches.add(candidate);
            else resolved.add(candidate);
        }
        if (!eligible.isEmpty() && evidence.values().stream().noneMatch(entry -> entry.status().equals("RESOLVED"))) {
            complete = false;
            errors.add("No successful DNS answer, including the root baseline; unresolved results may reflect a resolver outage");
        }
        SortedSet<String> hosts = new TreeSet<>(resolved);
        hosts.addAll(externalNames);
        Result result = new Result(hosts, generated, wildcardMatches, errors, complete,
                Map.of("wordlist-dns", resolved, "external-file", externalNames));
        Instant checked = clock.instant();
        Duration refresh = complete || ttl.compareTo(Duration.ofMinutes(30)) <= 0 ? ttl : Duration.ofMinutes(30);
        long nextCheck = checked.plus(refresh).toEpochMilli();
        write(directory.resolve("generated_candidates.txt"), new ArrayList<>(generated));
        write(directory.resolve("external_candidates.txt"), new ArrayList<>(externalNames));
        write(directory.resolve("wordlist_resolved.txt"), new ArrayList<>(resolved));
        write(directory.resolve("wildcard_matches.txt"), new ArrayList<>(wildcardMatches));
        write(directory.resolve("dns_status.csv"), dnsRows(generated, controls, evidence, checked, words.source(), wildcardMatches));
        write(directory.resolve("source_provenance.txt"), List.of(
                "wordlist_source=" + words.source(), "wordlist_sha256=" + words.hash(),
                "external_source=" + external.source(), "external_present=" + external.present(),
                "external_sha256=" + external.hash(), "fingerprint=" + fingerprint,
                "checked_at=" + checked, "next_check_at=" + Instant.ofEpochMilli(nextCheck)));
        List<String> summary = new ArrayList<>(List.of(checked.toString(),
                "generated_guesses=" + generated.size(), "wordlist_dns_positives=" + resolved.size(),
                "external_reported_names=" + externalNames.size(), "wildcard_matches_excluded=" + wildcardMatches.size(),
                "complete=" + complete,
                "Guesses are not reported/discovered hosts. External imports require downstream DNS/HTTP checks.",
                "UNRESOLVED does not prove NXDOMAIN. Random-control DNS matches are not promoted from guesses."));
        errors.forEach(error -> summary.add("ERROR " + error));
        write(directory.resolve("summary.txt"), summary);
        writeBytes(directory.resolve("cache.json"), JSON.writeValueAsBytes(new Cache(fingerprint, checked.toEpochMilli(), nextCheck, result)));
        return result;
    }

    private Cache readCache(Path file, String fingerprint, List<String> errors) {
        if (!Files.isRegularFile(file)) return null;
        try {
            Cache cached;
            try (InputStream input = Files.newInputStream(file)) { cached = JSON.readValue(readLimited(input), Cache.class); }
            if (!cached.fingerprint().equals(fingerprint) || cached.result() == null
                    || cached.nextCheckAt() < cached.checkedAt()) return null;
            Result result = cached.result();
            for (String name : result.hosts()) if (!validCachedHost(name)) return null;
            for (String name : result.generatedCandidates()) if (!validCachedHost(name)) return null;
            for (String name : result.wildcardMatches()) if (!result.generatedCandidates().contains(name)) return null;
            if (result.generatedCandidates().size() > MAX_CANDIDATES) return null;
            if (!result.bySource().keySet().equals(Set.of("wordlist-dns", "external-file"))) return null;
            if (!result.generatedCandidates().containsAll(result.bySource().get("wordlist-dns"))
                    || !Collections.disjoint(result.bySource().get("wordlist-dns"), result.wildcardMatches())) return null;
            if (result.generatedCandidates().size() + result.bySource().get("external-file").size() > MAX_CANDIDATES) return null;
            SortedSet<String> union = new TreeSet<>();
            for (var names : result.bySource().values()) {
                for (String name : names) if (!validCachedHost(name)) return null;
                union.addAll(names);
            }
            return union.equals(result.hosts()) ? cached : null;
        } catch (IOException | RuntimeException e) {
            errors.add("Wordlist cache was unreadable; DNS checks will be refreshed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean validCachedHost(String name) {
        return CertificateTransparencyDiscovery.normalizeName(name).filter(name::equals).isPresent();
    }

    private static Input input(Path file, boolean optional) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (optional && !Files.exists(absolute)) return new Input(absolute.toString(), new byte[0], false);
        try (InputStream stream = Files.newInputStream(absolute)) {
            return new Input(absolute.toString(), readLimited(stream), true);
        }
    }

    private static Parsed parse(Input input, int budget, boolean wordlist, List<String> errors) {
        SortedSet<String> names = new TreeSet<>();
        boolean truncated = false;
        int ignored = 0;
        for (String raw : input.text().split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("\uFEFF")) line = line.substring(1).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Optional<String> candidate;
            if (wordlist) {
                int comment = line.indexOf('#');
                if (comment >= 0) line = line.substring(0, comment).strip();
                candidate = word(line);
            } else {
                String field = firstCsvField(line);
                if (Set.of("host", "hostname", "domain", "subdomain", "url", "final_url").contains(field.toLowerCase(Locale.ROOT))) continue;
                candidate = external(field);
            }
            if (candidate.isEmpty()) { ignored++; continue; }
            String name = candidate.get();
            if (!names.contains(name) && names.size() >= budget) { truncated = true; break; }
            names.add(name);
        }
        if (ignored != 0) errors.add("Ignored " + ignored + " invalid/out-of-scope " + (wordlist ? "wordlist" : "external") + " entries in " + input.source());
        if (truncated) errors.add("Candidate budget reached in " + input.source() + "; remaining entries were not processed");
        return new Parsed(names, truncated);
    }

    private static Optional<String> word(String value) {
        value = value.toLowerCase(Locale.ROOT);
        Optional<String> full = CertificateTransparencyDiscovery.normalizeName(value);
        if (full.isPresent()) return full;
        if (value.equals(ROOT) || value.endsWith(".edu.vn") || value.endsWith(".")) return Optional.empty();
        return CertificateTransparencyDiscovery.normalizeName(value + "." + ROOT);
    }

    private static Optional<String> external(String value) {
        if (value.startsWith("//")) value = "https:" + value;
        if (value.contains("://")) {
            Optional<URI> uri = SubdomainDiscoverer.normalize(value);
            return uri.flatMap(url -> CertificateTransparencyDiscovery.normalizeName(url.getHost()));
        }
        return CertificateTransparencyDiscovery.normalizeName(value);
    }

    private static String firstCsvField(String line) {
        if (!line.startsWith("\"")) return line.split(",", 2)[0].strip();
        StringBuilder field = new StringBuilder();
        for (int i = 1; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character != '"') { field.append(character); continue; }
            if (i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
            else {
                String rest = line.substring(i + 1).stripLeading();
                return rest.isEmpty() || rest.startsWith(",") ? field.toString().strip() : "";
            }
        }
        return "";
    }

    private static String control(String zone) {
        return "hust-discovery-" + UUID.randomUUID().toString().replace("-", "") + "." + zone;
    }

    private static List<String> dnsRows(SortedSet<String> generated, Map<String, List<String>> controls,
                                       Map<String, DnsSubdomainVerifier.Entry> evidence, Instant checked,
                                       String source, Set<String> wildcardMatches) {
        List<String> rows = new ArrayList<>();
        rows.add("hostname,status,addresses,checked_at,kind,source,classification");
        for (String host : generated) {
            var answer = evidence.get(host);
            String status = answer == null ? "NOT_CHECKED" : answer.status();
            String addresses = answer == null ? "" : String.join(";", answer.addresses());
            String classification = wildcardMatches.contains(host) ? "WILDCARD_MATCH"
                    : status.equals("RESOLVED") ? "WORDLIST_DNS" : status;
            rows.add(csv(host, status, addresses, checked.toString(), "GENERATED_GUESS", source, classification));
        }
        Set<String> controlNames = new TreeSet<>();
        controls.values().forEach(controlNames::addAll);
        if (evidence.containsKey(ROOT)) controlNames.add(ROOT);
        for (String host : controlNames) {
            var answer = evidence.get(host);
            if (answer != null) rows.add(csv(host, answer.status(), String.join(";", answer.addresses()), checked.toString(),
                    host.equals(ROOT) ? "BASELINE" : "WILDCARD_CONTROL", source, "CONTROL_NOT_A_DISCOVERED_HOST"));
        }
        return rows;
    }

    private static String csv(String... fields) {
        return Arrays.stream(fields).map(field -> "\"" + field.replace("\"", "\"\"") + "\"").reduce((a, b) -> a + "," + b).orElse("");
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) throw new IOException("Discovery input exceeds 20 MiB");
        return bytes;
    }

    private static void write(Path destination, List<String> lines) throws IOException {
        writeBytes(destination, (String.join("\n", lines) + (lines.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(Path destination, byte[] bytes) throws IOException {
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "wordlist-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
