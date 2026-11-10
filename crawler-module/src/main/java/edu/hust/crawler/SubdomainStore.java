package edu.hust.crawler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Incremental registry. The first observed URL preserves HTTP-only sites and deep entry points. */
final class SubdomainStore {
    private final Path storage;
    private final Path seedOutput;
    private final Map<URI, URI> entries = new LinkedHashMap<>();
    private final Set<URI> confirmed = new HashSet<>();

    SubdomainStore(Path storage, Path seedOutput) throws IOException {
        this.storage = storage;
        this.seedOutput = seedOutput;
        Path urls = storage.resolve("discovered_seeds.txt");
        Path legacy = storage.resolve("discovered_subdomains.txt");
        if (Files.exists(urls)) load(Files.readAllLines(urls, StandardCharsets.UTF_8));
        else if (Files.exists(legacy)) load(Files.readAllLines(legacy, StandardCharsets.UTF_8));
    }

    private void load(List<String> lines) {
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            SubdomainDiscoverer.normalize(value.contains("://") ? value : "https://" + value)
                    .ifPresent(uri -> entries.putIfAbsent(SubdomainDiscoverer.origin(uri), uri));
        }
    }

    List<URI> entryPoints() { return List.copyOf(entries.values()); }

    Set<String> subdomains() {
        Set<String> result = new TreeSet<>();
        entries.values().forEach(uri -> { if (!uri.getHost().equals("hust.edu.vn")) result.add(uri.getHost()); });
        return Collections.unmodifiableSet(result);
    }

    void remember(Collection<URI> urls) throws IOException {
        boolean changed = false;
        for (URI url : urls) {
            Optional<URI> normalized = SubdomainDiscoverer.normalize(url.toString());
            if (normalized.isPresent()) {
                URI uri = normalized.get();
                if (entries.putIfAbsent(SubdomainDiscoverer.origin(uri), uri) == null) changed = true;
            }
        }
        if (changed) save();
    }

    void rememberFetched(URI url) throws IOException {
        URI uri = SubdomainDiscoverer.normalize(url.toString()).orElseThrow();
        URI origin = SubdomainDiscoverer.origin(uri);
        // Upgrade an old hostname-only or asset-derived entry to a URL that actually works.
        // Keep the first successful entry this round instead of adding every article as a seed.
        if (confirmed.add(origin)) {
            URI previous = entries.put(origin, uri);
            if (!uri.equals(previous)) save();
        }
    }

    void save() throws IOException {
        Files.createDirectories(storage);
        write(storage.resolve("discovered_subdomains.txt"), new ArrayList<>(subdomains()));
        List<String> urls = entries.values().stream().map(URI::toString).sorted().toList();
        write(storage.resolve("discovered_seeds.txt"), urls);
        Path catalog = storage.resolve("discovery/subdomains.txt");
        SortedSet<String> combined = new TreeSet<>(subdomains());
        if (Files.isRegularFile(catalog)) for (String name : Files.readAllLines(catalog, StandardCharsets.UTF_8))
            CertificateTransparencyDiscovery.normalizeName(name).ifPresent(combined::add);
        write(catalog, new ArrayList<>(combined));
        if (seedOutput != null) {
            List<String> lines = Files.exists(seedOutput)
                    ? new ArrayList<>(Files.readAllLines(seedOutput, StandardCharsets.UTF_8)) : new ArrayList<>();
            Set<URI> existing = new HashSet<>();
            for (String line : lines) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("#"))
                    SubdomainDiscoverer.normalize(value.contains("://") ? value : "https://" + value).ifPresent(existing::add);
            }
            Set<URI> origins = new HashSet<>();
            existing.forEach(uri -> origins.add(SubdomainDiscoverer.origin(uri)));
            // The resource is a bootstrap list, not an ever-growing list of every round's first article.
            // Working deep URLs continue to be refreshed in discovered_seeds.txt.
            for (String url : urls)
                if (origins.add(SubdomainDiscoverer.origin(URI.create(url)))) lines.add(url);
            write(seedOutput, lines);
        }
    }

    private static void write(Path destination, List<String> lines) throws IOException {
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "subdomains-", ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
