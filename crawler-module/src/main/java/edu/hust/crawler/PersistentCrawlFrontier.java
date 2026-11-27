package edu.hust.crawler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Durable URL queue for the single crawler process holding the application's instance lock. */
public final class PersistentCrawlFrontier implements AutoCloseable {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final Connection connection;
    private final Clock clock;
    private final long revisitMillis;

    public PersistentCrawlFrontier(Path database, Duration revisitInterval) throws Exception {
        this(database, revisitInterval, Clock.systemUTC());
    }

    PersistentCrawlFrontier(Path database, Duration revisitInterval, Clock clock) throws Exception {
        Objects.requireNonNull(revisitInterval, "revisitInterval");
        if (revisitInterval.isNegative())
            throw new IllegalArgumentException("Revisit interval must not be negative");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.revisitMillis = revisitInterval.toMillis();
        Path absolute = database.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE IF NOT EXISTS frontier_origins ("
                    + "origin TEXT PRIMARY KEY, not_before_ms INTEGER NOT NULL DEFAULT 0, "
                    + "last_polled INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS frontier_urls ("
                    + "id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, origin TEXT NOT NULL, "
                    + "next_due_ms INTEGER NOT NULL, last_success_ms INTEGER, "
                    + "lease_until_ms INTEGER NOT NULL DEFAULT 0, "
                    + "FOREIGN KEY(origin) REFERENCES frontier_origins(origin))");
            statement.execute("CREATE INDEX IF NOT EXISTS frontier_due ON frontier_urls(next_due_ms,lease_until_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS frontier_origin ON frontier_urls(origin,last_success_ms)");
        } catch (SQLException | RuntimeException e) {
            try { connection.close(); } catch (SQLException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }

    /** Rediscovery must never reset the lease, retry delay or revisit date of an existing URL. */
    public synchronized boolean add(URI uri) throws SQLException {
        return addAll(Collections.singletonList(uri)) != 0;
    }

    /** Adds a page's discovered links with one transaction; invalid or foreign URLs are ignored. */
    public synchronized int addAll(Collection<URI> urls) throws SQLException {
        Objects.requireNonNull(urls, "urls");
        Set<URI> normalized = new LinkedHashSet<>();
        for (URI uri : urls) canonical(uri).ifPresent(normalized::add);
        if (normalized.isEmpty()) return 0;
        long now = clock.millis();
        return transaction(() -> {
            int added = 0;
            try (PreparedStatement origins = connection.prepareStatement("INSERT OR IGNORE INTO frontier_origins(origin) VALUES (?)");
                 PreparedStatement pages = connection.prepareStatement("INSERT OR IGNORE INTO frontier_urls(url,origin,next_due_ms) VALUES (?,?,?)")) {
                for (URI uri : normalized) {
                    String origin = SubdomainDiscoverer.origin(uri).toString();
                    origins.setString(1, origin);
                    origins.executeUpdate();
                    pages.setString(1, uri.toString());
                    pages.setString(2, origin);
                    pages.setLong(3, now);
                    added += pages.executeUpdate();
                }
            }
            return added;
        });
    }

    /**
     * Leases one due URL. New work normally wins; every fifth poll reserves a turn for due updates.
     * Within each group, the least recently polled origin wins before another page from a busy site.
     */
    public synchronized Optional<URI> poll() throws SQLException {
        return poll(Set.of());
    }

    /** Per-round exclusions do not alter persisted due dates or prevent the next round's revisit. */
    public synchronized Optional<URI> poll(Set<URI> excluded) throws SQLException {
        Objects.requireNonNull(excluded, "excluded");
        Set<String> excludedUrls = new HashSet<>();
        for (URI uri : excluded) canonical(uri).map(URI::toString).ifPresent(excludedUrls::add);
        long now = clock.millis();
        return transaction(() -> {
            long sequence;
            try (Statement query = connection.createStatement();
                 ResultSet result = query.executeQuery("SELECT COALESCE(MAX(last_polled),0)+1 FROM frontier_origins")) {
                result.next();
                sequence = result.getLong(1);
            }
            String url;
            String origin;
            String sql = "SELECT u.url,u.origin FROM frontier_urls u JOIN frontier_origins o ON o.origin=u.origin "
                    + "WHERE u.next_due_ms<=? AND u.lease_until_ms<=? AND o.not_before_ms<=? "
                    + "ORDER BY CASE WHEN ?=1 THEN CASE WHEN u.last_success_ms IS NOT NULL THEN 0 ELSE 1 END "
                    + "ELSE CASE WHEN u.last_success_ms IS NULL THEN 0 ELSE 1 END END, "
                    + "o.last_polled,u.next_due_ms,u.id" + (excludedUrls.isEmpty() ? " LIMIT 1" : "");
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setLong(1, now);
                query.setLong(2, now);
                query.setLong(3, now);
                query.setInt(4, sequence % 5 == 0 ? 1 : 0);
                try (ResultSet result = query.executeQuery()) {
                    boolean found = false;
                    while (result.next()) {
                        if (!excludedUrls.contains(result.getString(1))) { found = true; break; }
                    }
                    if (!found) return Optional.empty();
                    url = result.getString(1);
                    origin = result.getString(2);
                }
            }
            try (PreparedStatement page = connection.prepareStatement("UPDATE frontier_urls SET lease_until_ms=? WHERE url=?");
                 PreparedStatement site = connection.prepareStatement("UPDATE frontier_origins SET last_polled=? WHERE origin=?")) {
                page.setLong(1, plus(now, LEASE.toMillis()));
                page.setString(2, url);
                page.executeUpdate();
                site.setLong(1, sequence);
                site.setString(2, origin);
                site.executeUpdate();
            }
            return Optional.of(URI.create(url));
        });
    }

    /** Acknowledge the requested (leased) URL, even if HTTP followed a redirect elsewhere. */
    public synchronized void succeeded(URI uri) throws SQLException {
        Optional<URI> normalized = canonical(uri);
        if (normalized.isEmpty()) return;
        long now = clock.millis();
        try (PreparedStatement update = connection.prepareStatement("UPDATE frontier_urls SET "
                + "last_success_ms=?,next_due_ms=?,lease_until_ms=0 WHERE url=?")) {
            update.setLong(1, now);
            update.setLong(2, plus(now, revisitMillis));
            update.setString(3, normalized.get().toString());
            update.executeUpdate();
        }
    }

    /** Retry delays are at least one second, so a failed request cannot create an immediate hot loop. */
    public synchronized void failed(URI uri, Duration retry) throws SQLException {
        Objects.requireNonNull(retry, "retry");
        if (retry.isNegative()) throw new IllegalArgumentException("Retry delay must not be negative");
        Optional<URI> normalized = canonical(uri);
        if (normalized.isEmpty()) return;
        try (PreparedStatement update = connection.prepareStatement("UPDATE frontier_urls SET next_due_ms=?,lease_until_ms=0 WHERE url=?")) {
            update.setLong(1, plus(clock.millis(), Math.max(1000, retry.toMillis())));
            update.setString(2, normalized.get().toString());
            update.executeUpdate();
        }
    }

    /** Extends an origin's cooldown without discarding its pages or shortening an existing deadline. */
    public synchronized void deferOrigin(URI uri, Instant until) throws SQLException {
        Objects.requireNonNull(until, "until");
        Optional<URI> normalized = canonical(uri);
        if (normalized.isEmpty()) return;
        try (PreparedStatement update = connection.prepareStatement("INSERT INTO frontier_origins(origin,not_before_ms) VALUES (?,?) "
                + "ON CONFLICT(origin) DO UPDATE SET not_before_ms=MAX(frontier_origins.not_before_ms,excluded.not_before_ms)")) {
            update.setString(1, SubdomainDiscoverer.origin(normalized.get()).toString());
            update.setLong(2, until.toEpochMilli());
            update.executeUpdate();
        }
    }

    /** A persisted future cooldown, also checked before HTTP redirects and robots requests. */
    public synchronized Optional<Instant> deferredUntil(URI uri) throws SQLException {
        Optional<URI> normalized = canonical(uri);
        if (normalized.isEmpty()) return Optional.empty();
        try (PreparedStatement query = connection.prepareStatement("SELECT not_before_ms FROM frontier_origins "
                + "WHERE origin=? AND not_before_ms>?")) {
            query.setString(1, SubdomainDiscoverer.origin(normalized.get()).toString());
            query.setLong(2, clock.millis());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(Instant.ofEpochMilli(result.getLong(1))) : Optional.empty();
            }
        }
    }

    /** Release a cancelled request immediately; its pre-existing due date and origin cooldown remain. */
    public synchronized void release(URI uri) throws SQLException {
        Optional<URI> normalized = canonical(uri);
        if (normalized.isEmpty()) return;
        try (PreparedStatement update = connection.prepareStatement("UPDATE frontier_urls SET lease_until_ms=0 WHERE url=?")) {
            update.setString(1, normalized.get().toString());
            update.executeUpdate();
        }
    }

    /** Total known URLs, including future retries, leased work and scheduled successful revisits. */
    public synchronized int pendingCount() throws SQLException {
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT COUNT(*) FROM frontier_urls")) {
            result.next();
            return Math.toIntExact(result.getLong(1));
        }
    }

    public synchronized int dueCount() throws SQLException {
        long now = clock.millis();
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM frontier_urls u "
                + "JOIN frontier_origins o ON o.origin=u.origin "
                + "WHERE u.next_due_ms<=? AND u.lease_until_ms<=? AND o.not_before_ms<=?")) {
            query.setLong(1, now);
            query.setLong(2, now);
            query.setLong(3, now);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return Math.toIntExact(result.getLong(1));
            }
        }
    }

    /** Earliest time at which a stored URL becomes eligible; empty means no known URLs. */
    public synchronized Optional<Instant> nextDueAt() throws SQLException {
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT MIN(MAX(u.next_due_ms,u.lease_until_ms,o.not_before_ms)) "
                     + "FROM frontier_urls u JOIN frontier_origins o ON o.origin=u.origin")) {
            result.next();
            long due = result.getLong(1);
            if (result.wasNull()) return Optional.empty();
            return Optional.of(Instant.ofEpochMilli(Math.max(clock.millis(), due)));
        }
    }

    private static Optional<URI> canonical(URI uri) {
        return uri == null ? Optional.empty() : SubdomainDiscoverer.normalize(uri.toString());
    }

    private static long plus(long timestamp, long milliseconds) {
        try { return Math.addExact(timestamp, milliseconds); }
        catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }

    @FunctionalInterface
    private interface SqlWork<T> { T run() throws SQLException; }

    private <T> T transaction(SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException | Error e) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { e.addSuppressed(rollbackFailure); }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
