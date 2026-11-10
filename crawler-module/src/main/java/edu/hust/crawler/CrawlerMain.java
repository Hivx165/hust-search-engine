package edu.hust.crawler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

public final class CrawlerMain {
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        Set<String> supported = Set.of("storage", "seeds", "subdomains-output", "discovery-mode", "max-documents", "max-visits", "delay-ms", "interval-minutes", "revisit-minutes", "round-minutes", "archives", "wordlist-enabled", "wordlist", "external-subdomains", "live-check", "export-only");
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length || !supported.contains(args[i].substring(2)))
                throw new IllegalArgumentException("Usage: --storage path --seeds file --subdomains-output file --discovery-mode hybrid|links|passive --max-documents 1000 --max-visits 10000 --delay-ms 1000 --interval-minutes 5 --revisit-minutes 60 --round-minutes 15");
            options.put(args[i].substring(2), args[i + 1]);
        }
        Path storage = Path.of(options.getOrDefault("storage", "storage"));
        int max = Integer.parseInt(options.getOrDefault("max-documents", "1000"));
        int visits = Integer.parseInt(options.getOrDefault("max-visits", "10000"));
        long delay = Long.parseLong(options.getOrDefault("delay-ms", "1000"));
        long interval = Long.parseLong(options.getOrDefault("interval-minutes", "0"));
        long revisit = Long.parseLong(options.getOrDefault("revisit-minutes", "60"));
        long roundMinutes = Long.parseLong(options.getOrDefault("round-minutes", "15"));
        String mode = options.getOrDefault("discovery-mode", "hybrid");
        if (!Set.of("hybrid", "links", "passive").contains(mode)) throw new IllegalArgumentException("Unknown discovery mode: " + mode);
        if (max < 1 || visits < 1 || delay < 0 || interval < 0 || revisit < 1 || roundMinutes < 1) throw new IllegalArgumentException("Invalid limits");
        Files.createDirectories(storage);
        try (FileChannel channel = FileChannel.open(storage.resolve("crawler.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock acquired;
            try { acquired = channel.tryLock(); } catch (OverlappingFileLockException e) { acquired = null; }
            if (acquired == null) { System.err.println("A crawler is already running for " + storage.toAbsolutePath()); return; }
            try (FileLock lock = acquired; ChangeDetectionService state = new ChangeDetectionService(storage.resolve("crawler.db"))) {
            Files.writeString(storage.resolve("crawler.pid"), Long.toString(ProcessHandle.current().pid()));
            if (flag(options, "export-only", false)) {
                lifecycle(storage, "exporting", "Exporting saved pages and observed links without network requests");
                try { state.exportManifest(storage.resolve("manifest.csv")); }
                finally { System.out.println(CrawlDatasetExporter.export(state, storage)); }
                lifecycle(storage, "complete", "Dataset export finished");
                return;
            }
            Path output = options.containsKey("subdomains-output") ? Path.of(options.get("subdomains-output")) : null;
            HustWebCrawler crawler = new HustWebCrawler(storage, state, delay, output, Duration.ofMinutes(revisit), Duration.ofMinutes(roundMinutes));
            PassiveDiscoveryRunner passive = new PassiveDiscoveryRunner(flag(options, "archives", true), flag(options, "wordlist-enabled", true),
                    flag(options, "live-check", true), options.containsKey("wordlist") ? Path.of(options.get("wordlist")) : null,
                    Path.of(options.getOrDefault("external-subdomains", "data/external-subdomains.txt")));
            Runnable round = () -> {
                try {
                    if (!mode.equals("links")) {
                        lifecycle(storage, "passive_discovery", "Refreshing certificate/DNS evidence");
                        try { passive.discover(storage, output); }
                        catch (IOException e) { System.err.println("Passive discovery failed; continuing link crawl: " + e.getMessage()); }
                    }
                    if (!mode.equals("passive")) {
                        lifecycle(storage, "crawling", "Processing durable URL queue");
                        System.out.println(crawler.crawl(loadSeeds(options), max, visits));
                    }
                    lifecycle(storage, interval > 0 ? "waiting" : "complete", "Round finished; interval minutes=" + interval);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) {
                    System.err.println("Crawl round failed: " + e);
                    try { lifecycle(storage, "error", e.toString()); } catch (IOException ignored) { }
                    if (interval == 0) throw new RuntimeException(e);
                }
            };
            if (interval == 0) {
                round.run();
            }
            else try (CrawlerScheduler scheduler = new CrawlerScheduler()) {
                CountDownLatch stop = new CountDownLatch(1);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> { scheduler.close(); stop.countDown(); }));
                scheduler.start(round, Duration.ofMinutes(interval));
                stop.await();
                scheduler.close();
            }
            } finally { Files.deleteIfExists(storage.resolve("crawler.pid")); }
        }
    }

    private static List<URI> loadSeeds(Map<String, String> options) throws IOException {
        List<URI> seeds = new ArrayList<>();
        try (InputStream input = options.containsKey("seeds") ? Files.newInputStream(Path.of(options.get("seeds")))
                : CrawlerMain.class.getResourceAsStream("/initial_subdomains.txt")) {
            if (input == null) throw new IOException("Seed list missing");
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("#"))
                    seeds.add(SubdomainDiscoverer.normalize(value.contains("://") ? value : "https://" + value)
                            .orElseThrow(() -> new IllegalArgumentException("Invalid HUST seed: " + value)));
            }
        }
        if (seeds.isEmpty()) throw new IllegalArgumentException("No seeds");
        return seeds;
    }
    private static boolean flag(Map<String, String> options, String key, boolean fallback) {
        String value = options.getOrDefault(key, Boolean.toString(fallback));
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("--" + key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
    private static void lifecycle(Path storage, String phase, String detail) throws IOException {
        Properties status = new Properties();
        status.setProperty("phase", phase); status.setProperty("updated_at", java.time.Instant.now().toString());
        status.setProperty("detail", detail); status.setProperty("process_id", Long.toString(ProcessHandle.current().pid()));
        try (Writer writer = Files.newBufferedWriter(storage.resolve("lifecycle.properties"), StandardCharsets.UTF_8)) {
            status.store(writer, "Crawler lifecycle");
        }
    }
}
