package edu.hust.crawler;

import java.time.Duration;
import java.util.concurrent.*;

/** Single worker and fixed delay prevent overlapping crawl rounds. */
public final class CrawlerScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    public void start(Runnable crawl, Duration interval) {
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("Interval must be positive");
        executor.scheduleWithFixedDelay(() -> {
            try { crawl.run(); } catch (Exception e) { System.err.println("Crawl round failed: " + e.getMessage()); }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public void close() {
        executor.shutdownNow();
        try { executor.awaitTermination(35, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
