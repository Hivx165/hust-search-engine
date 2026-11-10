package edu.hust.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WordlistSubdomainDiscoveryTest {
    @TempDir Path temp;

    @Test void onlyDnsPositiveGuessesBecomeReportedHostsAndProvenanceIsSaved() throws Exception {
        Path words = temp.resolve("words.txt");
        Files.writeString(words, "\uFEFFbom-first\n" + """
                # Generated hypotheses, not discovered names.
                WWW
                www
                mail
                nested.department
                deep.hust.edu.vn
                missing
                bad_label
                -bad
                *.faculty.hust.edu.vn
                https://outside.example/
                user@hust.edu.vn
                evilhust.edu.vn
                hust.edu.vn
                """);
        Set<String> queried = ConcurrentHashMap.newKeySet();
        var discovery = new WordlistSubdomainDiscovery(host -> {
            queried.add(host);
            if (host.equals("hust.edu.vn")) return Set.of("192.0.2.1");
            if (Set.of("www.hust.edu.vn", "nested.department.hust.edu.vn", "deep.hust.edu.vn").contains(host))
                return Set.of("192.0.2.2");
            if (host.equals("mail.hust.edu.vn")) throw new IOException("fixture unresolved");
            return Set.of();
        }, new MutableClock(), Duration.ofDays(1));

        var result = discovery.discover(temp, words, temp.resolve("missing-external.txt"));
        assertEquals(Set.of("www.hust.edu.vn", "nested.department.hust.edu.vn", "deep.hust.edu.vn"), result.hosts());
        assertEquals(Set.of("www.hust.edu.vn", "nested.department.hust.edu.vn", "deep.hust.edu.vn",
                "mail.hust.edu.vn", "missing.hust.edu.vn", "bom-first.hust.edu.vn"), result.generatedCandidates());
        assertEquals(result.hosts(), result.bySource().get("wordlist-dns"));
        assertTrue(result.bySource().get("external-file").isEmpty());
        assertTrue(result.wildcardMatches().isEmpty());
        assertTrue(result.complete());
        assertEquals(4, queried.stream().filter(host -> host.startsWith("hust-discovery-")).count());
        assertFalse(result.hosts().contains("missing.hust.edu.vn"));
        String evidence = Files.readString(temp.resolve("wordlist-discovery/dns_status.csv"));
        assertTrue(evidence.contains("\"missing.hust.edu.vn\",\"UNRESOLVED\""));
        assertTrue(Files.readString(temp.resolve("wordlist-discovery/source_provenance.txt")).contains(words.toAbsolutePath().toString()));
        assertThrows(UnsupportedOperationException.class, () -> result.bySource().get("wordlist-dns").add("x.hust.edu.vn"));
    }

    @Test void wildcardDnsAtRootAndNestedZonesDoesNotPromoteGuessedMatches() throws Exception {
        Path words = temp.resolve("words.txt");
        Files.writeString(words, "mail\nreal\nnested.department\nunique.department\n");
        var discovery = new WordlistSubdomainDiscovery(host -> {
            if (host.startsWith("hust-discovery-"))
                return Set.of(host.endsWith(".department.hust.edu.vn") ? "192.0.2.200" : "192.0.2.100");
            return switch (host) {
                case "hust.edu.vn" -> Set.of("192.0.2.1");
                case "mail.hust.edu.vn" -> Set.of("192.0.2.100");
                case "nested.department.hust.edu.vn" -> Set.of("192.0.2.200");
                case "real.hust.edu.vn" -> Set.of("192.0.2.101");
                case "unique.department.hust.edu.vn" -> Set.of("192.0.2.201");
                default -> Set.of();
            };
        }, new MutableClock(), Duration.ZERO);
        var result = discovery.discover(temp, words, null);
        assertEquals(Set.of("mail.hust.edu.vn", "nested.department.hust.edu.vn"), result.wildcardMatches());
        assertEquals(Set.of("real.hust.edu.vn", "unique.department.hust.edu.vn"), result.hosts());
        assertEquals(4, result.generatedCandidates().size());
        assertTrue(result.complete());
        assertTrue(result.hosts().stream().noneMatch(host -> host.startsWith("hust-discovery-")));
        assertTrue(Files.readString(temp.resolve("wordlist-discovery/dns_status.csv")).contains("WILDCARD_MATCH"));
    }

    @Test void externalCsvHostsAndUrlsImportWithoutDnsWhenWordlistIsDisabled() throws Exception {
        Path external = temp.resolve("external-subdomains.txt");
        Files.writeString(external, "\uFEFFbom-external.hust.edu.vn\n" + """
                hostname,ip,status
                "EXTERNAL.HUST.EDU.VN.",192.0.2.10,200
                http://nested.hust.edu.vn/directory,192.0.2.11,200
                "https://DEEP.hust.edu.vn/article?q=a,b",192.0.2.12,200
                external.hust.edu.vn
                https://hust.edu.vn.evil.com/
                evilhust.edu.vn
                *.faculty.hust.edu.vn
                hust.edu.vn
                https://user:password@private.hust.edu.vn/
                """);
        var discovery = new WordlistSubdomainDiscovery(host -> {
            throw new AssertionError("Disabled wordlist must not perform DNS, including controls");
        }, new MutableClock(), Duration.ofDays(1));
        var result = discovery.discover(temp, temp.resolve("unused-missing-wordlist.txt"), external, false);
        assertEquals(Set.of("external.hust.edu.vn", "nested.hust.edu.vn", "deep.hust.edu.vn", "bom-external.hust.edu.vn"), result.hosts());
        assertEquals(result.hosts(), result.bySource().get("external-file"));
        assertTrue(result.bySource().get("wordlist-dns").isEmpty());
        assertTrue(result.generatedCandidates().isEmpty());
        assertTrue(result.complete());
        assertTrue(Files.readString(temp.resolve("wordlist-discovery/generated_candidates.txt")).isEmpty());
        assertTrue(Files.readString(temp.resolve("wordlist-discovery/source_provenance.txt")).contains("wordlist:disabled"));
    }

    @Test void cacheSurvivesRestartAndRefreshesWhenInputFilesOrTtlChange() throws Exception {
        Path words = temp.resolve("words.txt"), external = temp.resolve("external.txt");
        Files.writeString(words, "www\n");
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        DnsSubdomainVerifier.Resolver resolver = host -> {
            calls.incrementAndGet();
            return host.equals("hust.edu.vn") || host.equals("www.hust.edu.vn") || host.equals("mail.hust.edu.vn")
                    ? Set.of("192.0.2.1") : Set.of();
        };
        var first = new WordlistSubdomainDiscovery(resolver, clock, Duration.ofDays(1));
        assertEquals(Set.of("www.hust.edu.vn"), first.discover(temp, words, external).hosts());
        int initialCalls = calls.get();
        var restarted = new WordlistSubdomainDiscovery(resolver, clock, Duration.ofDays(1));
        assertEquals(Set.of("www.hust.edu.vn"), restarted.discover(temp, words, external).hosts());
        assertEquals(initialCalls, calls.get());

        Files.writeString(external, "reported.hust.edu.vn\n");
        assertEquals(Set.of("www.hust.edu.vn", "reported.hust.edu.vn"), restarted.discover(temp, words, external).hosts());
        assertTrue(calls.get() > initialCalls);
        int afterExternal = calls.get();
        Files.writeString(words, "www\nmail\n");
        var changed = restarted.discover(temp, words, external);
        assertEquals(Set.of("www.hust.edu.vn", "mail.hust.edu.vn", "reported.hust.edu.vn"), changed.hosts());
        assertTrue(calls.get() > afterExternal);
        int afterWords = calls.get();
        clock.advance(Duration.ofHours(23));
        restarted.discover(temp, words, external);
        assertEquals(afterWords, calls.get());
        clock.advance(Duration.ofHours(1));
        restarted.discover(temp, words, external);
        assertTrue(calls.get() > afterWords);
    }

    @Test void dnsOutageIsIncompleteAndGetsAShorterRetryThanHealthyEvidence() throws Exception {
        Path words = temp.resolve("words.txt");
        Files.writeString(words, "www\n");
        var clock = new MutableClock();
        var outage = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var discovery = new WordlistSubdomainDiscovery(host -> {
            calls.incrementAndGet();
            if (outage.get()) throw new IOException("fixture DNS outage");
            return host.equals("hust.edu.vn") || host.equals("www.hust.edu.vn") ? Set.of("192.0.2.1") : Set.of();
        }, clock, Duration.ofDays(1));
        var result = discovery.discover(temp, words, null);
        assertFalse(result.complete());
        assertTrue(result.hosts().isEmpty());
        assertEquals(Set.of("www.hust.edu.vn"), result.generatedCandidates());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("resolver outage")));
        int previous = calls.get();
        clock.advance(Duration.ofMinutes(20));
        discovery.discover(temp, words, null);
        assertEquals(previous, calls.get());
        outage.set(false);
        clock.advance(Duration.ofMinutes(10));
        assertTrue(discovery.discover(temp, words, null).complete());
        assertTrue(calls.get() > previous);
    }

    @Test void candidateAndZoneBudgetsAreExplicitlyIncomplete() throws Exception {
        Path many = temp.resolve("many.txt");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 5001; i++) words.add("service" + i);
        Files.write(many, words);
        var discovery = new WordlistSubdomainDiscovery(host -> host.equals("hust.edu.vn") ? Set.of("192.0.2.1") : Set.of(),
                new MutableClock(), Duration.ZERO);
        var capped = discovery.discover(temp.resolve("candidate-limit"), many, null);
        assertEquals(5000, capped.generatedCandidates().size());
        assertTrue(capped.hosts().isEmpty());
        assertFalse(capped.complete());
        assertTrue(capped.errors().stream().anyMatch(error -> error.contains("Candidate budget")));

        Path zones = temp.resolve("zones.txt");
        words.clear();
        for (int i = 0; i < 101; i++) words.add("service.zone" + i);
        Files.write(zones, words);
        var zoneDiscovery = new WordlistSubdomainDiscovery(host -> host.startsWith("hust-discovery-") ? Set.of() : Set.of("192.0.2.1"),
                new MutableClock(), Duration.ZERO);
        var zoneCapped = zoneDiscovery.discover(temp.resolve("zone-limit"), zones, null);
        assertEquals(101, zoneCapped.generatedCandidates().size());
        assertEquals(100, zoneCapped.hosts().size());
        assertFalse(zoneCapped.complete());
        assertTrue(zoneCapped.errors().stream().anyMatch(error -> error.contains("Wildcard-control")));
    }

    @Test void builtInWordlistRemainsSmallAndIsOnlyADiagnosticHypothesisList() throws Exception {
        var discovery = new WordlistSubdomainDiscovery(host -> host.equals("hust.edu.vn") ? Set.of("192.0.2.1") : Set.of(),
                new MutableClock(), Duration.ZERO);
        var result = discovery.discover(temp, null, null);
        assertFalse(result.generatedCandidates().isEmpty());
        assertTrue(result.generatedCandidates().size() <= 150);
        assertTrue(result.generatedCandidates().contains("www.hust.edu.vn"));
        assertTrue(result.hosts().isEmpty());
    }

    @Test void interruptionPropagatesWithoutWritingASuccessfulCache() throws Exception {
        Path words = temp.resolve("words.txt");
        Files.writeString(words, "www\n");
        var discovery = new WordlistSubdomainDiscovery(host -> { throw new InterruptedException("fixture cancellation"); },
                new MutableClock(), Duration.ZERO);
        assertThrows(InterruptedException.class, () -> discovery.discover(temp, words, null));
        assertFalse(Files.exists(temp.resolve("wordlist-discovery/cache.json")));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-07T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
