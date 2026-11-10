package edu.hust.crawler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** Runs passive discovery before the ordinary link crawl; source outages retain cached candidates. */
public final class PassiveDiscoveryRunner {
    private final CertificateTransparencyDiscovery source;
    private final DnsSubdomainVerifier verifier;
    private final ArchiveSubdomainDiscovery archives;
    private final WordlistSubdomainDiscovery wordlist;
    private final LiveHostProbe liveProbe;
    private final Path wordlistFile, externalFile;
    private final boolean wordlistEnabled;

    public record Report(int reportedHosts, int dnsResolvedHosts, int wildcardPatterns,
                         boolean sourceComplete, List<String> sourceErrors) {}

    public PassiveDiscoveryRunner() { this(true, true, true, null, Path.of("data/external-subdomains.txt")); }
    public PassiveDiscoveryRunner(boolean archivesEnabled, boolean wordlistEnabled, boolean liveCheck, Path wordlistFile, Path externalFile) {
        this(new CertificateTransparencyDiscovery(), new DnsSubdomainVerifier(),
                archivesEnabled ? new ArchiveSubdomainDiscovery() : null,
                wordlistEnabled || externalFile != null ? new WordlistSubdomainDiscovery() : null,
                liveCheck ? new LiveHostProbe() : null,
                wordlistFile, externalFile, wordlistEnabled);
    }
    PassiveDiscoveryRunner(CertificateTransparencyDiscovery source, DnsSubdomainVerifier verifier) {
        this(source, verifier, null, null, null, null, null);
    }
    PassiveDiscoveryRunner(CertificateTransparencyDiscovery source, DnsSubdomainVerifier verifier,
                           ArchiveSubdomainDiscovery archives, WordlistSubdomainDiscovery wordlist, LiveHostProbe liveProbe,
                           Path wordlistFile, Path externalFile) {
        this(source, verifier, archives, wordlist, liveProbe, wordlistFile, externalFile, true);
    }
    private PassiveDiscoveryRunner(CertificateTransparencyDiscovery source, DnsSubdomainVerifier verifier,
                           ArchiveSubdomainDiscovery archives, WordlistSubdomainDiscovery wordlist, LiveHostProbe liveProbe,
                           Path wordlistFile, Path externalFile, boolean wordlistEnabled) {
        this.source = source; this.verifier = verifier; this.archives = archives; this.wordlist = wordlist;
        this.liveProbe = liveProbe; this.wordlistFile = wordlistFile; this.externalFile = externalFile;
        this.wordlistEnabled = wordlistEnabled;
    }

    public Report discover(Path storage, Path seedOutput) throws IOException, InterruptedException {
        Path directory = storage.resolve("passive-discovery");
        Files.createDirectories(directory);
        Path candidatesFile = directory.resolve("candidates.txt");
        Path wildcardsFile = directory.resolve("wildcards.txt");
        SortedSet<String> candidates = new TreeSet<>(), wildcards = load(wildcardsFile);
        for (String cached : load(candidatesFile)) CertificateTransparencyDiscovery.normalizeName(cached).ifPresent(candidates::add);
        CertificateTransparencyDiscovery.Result result = source.discover(storage);
        candidates.addAll(result.hosts());
        wildcards.addAll(result.wildcards());
        boolean complete = result.complete();
        List<String> errors = new ArrayList<>(result.errors());
        Map<String, SortedSet<String>> sources = new TreeMap<>();
        sources.put("certificate-transparency", result.hosts());
        SortedSet<String> known = new TreeSet<>(new SubdomainStore(storage, null).subdomains());
        candidates.addAll(known); sources.put("crawler-known", known);
        if (archives != null) {
            try {
                var archiveResult = archives.discover(storage);
                candidates.addAll(archiveResult.hosts()); sources.putAll(archiveResult.bySource());
                complete &= archiveResult.complete(); errors.addAll(archiveResult.errors());
            } catch (IOException e) { complete = false; errors.add("archives: " + e.getMessage()); }
        }
        if (wordlist != null) {
            try {
                var wordlistResult = wordlist.discover(storage, wordlistFile, externalFile, wordlistEnabled);
                candidates.addAll(wordlistResult.hosts()); sources.putAll(wordlistResult.bySource());
                complete &= wordlistResult.complete(); errors.addAll(wordlistResult.errors());
            } catch (IOException e) { complete = false; errors.add("wordlist/external: " + e.getMessage()); }
        }
        write(candidatesFile, new ArrayList<>(candidates));
        write(wildcardsFile, new ArrayList<>(wildcards));
        System.out.printf("Discovery catalog: %d concrete hosts, %d wildcard patterns, sourcesComplete=%s%n",
                candidates.size(), wildcards.size(), complete);
        Map<String, DnsSubdomainVerifier.Entry> evidence = new TreeMap<>();
        Map<String, String> checkedTimes = new HashMap<>();
        Map<String, SortedSet<String>> provenance = new TreeMap<>();
        Path dnsFile = directory.resolve("dns_status.csv");
        Instant now = Instant.now();
        if (Files.exists(dnsFile)) {
            for (String row : Files.readAllLines(dnsFile, StandardCharsets.UTF_8)) {
                String[] fields = row.split(",", 5);
                if (fields.length != 5 || !candidates.contains(fields[0])) continue;
                provenance.computeIfAbsent(fields[0], key -> new TreeSet<>()).addAll(Arrays.asList(fields[4].split("\\|")));
                try {
                    Duration ttl = fields[1].equals("RESOLVED") ? Duration.ofHours(6) : Duration.ofMinutes(30);
                    if (Instant.parse(fields[3]).plus(ttl).isBefore(now)) continue;
                    if (!Set.of("RESOLVED", "UNRESOLVED").contains(fields[1])) continue;
                    Set<String> addresses = fields[2].isBlank() ? Set.of() : new TreeSet<>(Arrays.asList(fields[2].split(";")));
                    if (fields[1].equals("RESOLVED") && addresses.isEmpty()) continue;
                    evidence.put(fields[0], new DnsSubdomainVerifier.Entry(fields[0], fields[1], addresses));
                    checkedTimes.put(fields[0], fields[3]);
                } catch (java.time.format.DateTimeParseException ignored) { }
            }
        }
        List<String> unchecked = candidates.stream().filter(host -> !evidence.containsKey(host)).toList();
        for (DnsSubdomainVerifier.Entry entry : verifier.verify(unchecked)) {
            evidence.put(entry.hostname(), entry); checkedTimes.put(entry.hostname(), now.toString());
        }
        List<DnsSubdomainVerifier.Entry> entries = new ArrayList<>(evidence.values());
        List<String> csv = new ArrayList<>();
        csv.add("hostname,status,addresses,checked_at,source");
        List<URI> seeds = new ArrayList<>();
        String checked = Instant.now().toString();
        for (var sourceEntry : sources.entrySet()) for (String host : sourceEntry.getValue())
            provenance.computeIfAbsent(host, key -> new TreeSet<>()).add(sourceEntry.getKey());
        for (DnsSubdomainVerifier.Entry entry : entries) {
            csv.add(String.join(",", entry.hostname(), entry.status(), String.join(";", new TreeSet<>(entry.addresses())),
                    checkedTimes.get(entry.hostname()), String.join("|", provenance.getOrDefault(entry.hostname(), new TreeSet<>(Set.of("cache"))))));
            if (entry.status().equals("RESOLVED")) seeds.add(URI.create("https://" + entry.hostname() + "/"));
        }
        write(directory.resolve("dns_status.csv"), csv);
        // Existing navigation URLs retain their scheme/path when adding passive HTTPS seeds.
        SubdomainStore store = new SubdomainStore(storage, seedOutput);
        store.remember(seeds);
        store.save();
        Path discoveryDirectory = storage.resolve("discovery");
        Files.createDirectories(discoveryDirectory);
        write(discoveryDirectory.resolve("subdomains.txt"), new ArrayList<>(candidates));
        List<String> sourceCounts = new ArrayList<>(List.of("source,unique_hosts"));
        for (var sourceEntry : sources.entrySet()) sourceCounts.add(sourceEntry.getKey() + "," + sourceEntry.getValue().size());
        write(discoveryDirectory.resolve("source_counts.csv"), sourceCounts);
        if (liveProbe != null) {
            var live = liveProbe.probe(entries, storage);
            System.out.println("HTTP evidence: " + live.stream().filter(row -> row.httpStatus() > 0).count() + "/" + live.size()
                    + " hosts with an HTTP response; see discovery/live-hosts.csv for unresolved, queued, robots and cooldown statuses");
        }
        Report report = new Report(candidates.size(), seeds.size(), wildcards.size(), complete, errors);
        write(directory.resolve("summary.txt"), List.of(checked, report.toString(),
                "DNS resolution does not prove HTTP availability. Wildcard patterns are not concrete hosts."));
        System.out.println(report);
        return report;
    }

    private static SortedSet<String> load(Path file) throws IOException {
        return Files.exists(file) ? new TreeSet<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new TreeSet<>();
    }

    private static void write(Path destination, List<String> lines) throws IOException {
        Path temp = Files.createTempFile(destination.toAbsolutePath().getParent(), "passive-", ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
