package edu.hust.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistentCrawlFrontierTest {
    @TempDir Path temp;
    private static final Duration REVISIT = Duration.ofHours(6);

    @Test void batchNormalizesDeduplicatesAndDoesNotResetExistingLease() throws Exception {
        var clock = new MutableClock();
        try (var queue = new PersistentCrawlFrontier(temp.resolve("frontier.db"), REVISIT, clock)) {
            assertEquals(2, queue.addAll(Arrays.asList(
                    uri("HTTPS://SOICT.HUST.EDU.VN:443/a#first"), uri("https://soict.hust.edu.vn/a#second"),
                    uri("http://soict.hust.edu.vn/a"), uri("https://hust.edu.vn.evil.com/a"),
                    uri("https://evilhust.edu.vn/a"), uri("file:///tmp/file"), null)));
            assertFalse(queue.add(uri("https://soict.hust.edu.vn/a")));
            assertEquals(2, queue.pendingCount());
            assertEquals(2, queue.dueCount());
            assertEquals(clock.instant(), queue.nextDueAt().orElseThrow());
            URI first = queue.poll().orElseThrow();
            assertEquals(uri("https://soict.hust.edu.vn/a"), first);
            assertFalse(queue.add(first));
            assertEquals(1, queue.dueCount());
            assertEquals(2, queue.pendingCount());
        }
    }

    @Test void unfinishedRoundSurvivesRestartWithoutLosingUnconsumedPages() throws Exception {
        var clock = new MutableClock();
        Path database = temp.resolve("frontier.db");
        URI a = uri("https://a.hust.edu.vn/1"), b = uri("https://b.hust.edu.vn/1"),
                remaining = uri("https://a.hust.edu.vn/2");
        try (var queue = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            queue.addAll(List.of(a, b, remaining));
            assertEquals(a, queue.poll().orElseThrow());
            queue.succeeded(a);
        }
        try (var resumed = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            assertEquals(3, resumed.pendingCount());
            assertEquals(2, resumed.dueCount());
            assertEquals(b, resumed.poll().orElseThrow());
            resumed.succeeded(b);
            assertEquals(remaining, resumed.poll().orElseThrow());
            resumed.succeeded(remaining);
            assertTrue(resumed.poll().isEmpty());
            assertEquals(0, resumed.dueCount());
            assertEquals(clock.instant().plus(REVISIT), resumed.nextDueAt().orElseThrow());
            assertFalse(resumed.add(a));
            assertEquals(0, resumed.dueCount());
            clock.advance(REVISIT);
            assertEquals(3, resumed.dueCount());
        }
    }

    @Test void originFairnessPreventsLargeSiteFromCrowdingOutSmallerSites() throws Exception {
        var clock = new MutableClock();
        try (var queue = new PersistentCrawlFrontier(temp.resolve("frontier.db"), REVISIT, clock)) {
            List<URI> many = new ArrayList<>();
            for (int i = 0; i < 1000; i++) many.add(uri("https://a.hust.edu.vn/" + i));
            queue.addAll(many);
            queue.addAll(List.of(uri("https://b.hust.edu.vn/0"), uri("https://b.hust.edu.vn/1")));
            for (String expected : List.of("https://a.hust.edu.vn/0", "https://b.hust.edu.vn/0",
                    "https://a.hust.edu.vn/1", "https://b.hust.edu.vn/1")) {
                URI next = queue.poll().orElseThrow();
                assertEquals(uri(expected), next);
                queue.succeeded(next);
            }
            URI newOrigin = uri("https://c.hust.edu.vn/directory");
            queue.add(newOrigin);
            assertEquals(newOrigin, queue.poll().orElseThrow());
            assertEquals(1003, queue.pendingCount());
        }
    }

    @Test void dueRevisitGetsAFifthTurnEvenWithManyNewUrlsAndAfterRestart() throws Exception {
        var clock = new MutableClock();
        Path database = temp.resolve("frontier.db");
        URI old = uri("https://updates.hust.edu.vn/article");
        Set<URI> alreadyThisRound = new HashSet<>();
        try (var queue = new PersistentCrawlFrontier(database, Duration.ZERO, clock)) {
            queue.add(old);
            assertEquals(old, queue.poll().orElseThrow()); // Poll 1.
            queue.succeeded(old);
            for (int i = 0; i < 20; i++) queue.add(uri("https://news.hust.edu.vn/" + i));
            for (int i = 0; i < 3; i++) { // Polls 2, 3 and 4 prefer previously unvisited pages.
                URI next = queue.poll(alreadyThisRound).orElseThrow();
                assertNotEquals(old, next);
                alreadyThisRound.add(next);
                queue.succeeded(next);
            }
        }
        try (var resumed = new PersistentCrawlFrontier(database, Duration.ZERO, clock)) {
            assertEquals(old, resumed.poll(alreadyThisRound).orElseThrow()); // Poll 5 retains the update budget.
            assertEquals(21, resumed.pendingCount());
        }
    }

    @Test void perRoundExclusionsAllowZeroIntervalWithoutChangingDueDates() throws Exception {
        var clock = new MutableClock();
        try (var queue = new PersistentCrawlFrontier(temp.resolve("frontier.db"), Duration.ZERO, clock)) {
            URI a = uri("https://a.hust.edu.vn/"), b = uri("https://b.hust.edu.vn/");
            queue.addAll(List.of(a, b));
            assertEquals(a, queue.poll().orElseThrow());
            queue.succeeded(a);
            assertEquals(b, queue.poll(Set.of(a)).orElseThrow());
            queue.succeeded(b);
            assertTrue(queue.poll(Set.of(a, b)).isEmpty());
            assertEquals(2, queue.dueCount());
            assertEquals(2, queue.pendingCount());
            assertEquals(clock.instant(), queue.nextDueAt().orElseThrow());
            assertEquals(a, queue.poll().orElseThrow());
        }
    }

    @Test void retryAndOriginCooldownPersistAndCannotBeShortenedByRediscovery() throws Exception {
        var clock = new MutableClock();
        Path database = temp.resolve("frontier.db");
        URI a = uri("https://a.hust.edu.vn/1"), anotherA = uri("https://a.hust.edu.vn/2"),
                b = uri("https://b.hust.edu.vn/");
        Instant originDeadline = clock.instant().plus(Duration.ofMinutes(20));
        try (var queue = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            queue.addAll(List.of(a, anotherA, b));
            assertTrue(queue.deferredUntil(a).isEmpty());
            queue.deferOrigin(a, originDeadline);
            queue.deferOrigin(anotherA, clock.instant().plusSeconds(30));
            assertEquals(originDeadline, queue.deferredUntil(anotherA).orElseThrow());
            assertTrue(queue.deferredUntil(b).isEmpty());
            assertTrue(queue.deferredUntil(uri("https://example.com/")).isEmpty());
            assertEquals(1, queue.dueCount());
            assertEquals(b, queue.poll().orElseThrow());
            queue.succeeded(b);
            assertTrue(queue.poll().isEmpty());
            assertEquals(originDeadline, queue.nextDueAt().orElseThrow());
        }
        try (var queue = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            assertEquals(0, queue.dueCount());
            assertEquals(originDeadline, queue.deferredUntil(a).orElseThrow());
            clock.advance(Duration.ofMinutes(20));
            assertTrue(queue.deferredUntil(a).isEmpty());
            assertEquals(2, queue.dueCount());
            assertEquals(a, queue.poll().orElseThrow());
            queue.failed(a, Duration.ZERO);
            assertEquals(anotherA, queue.poll().orElseThrow());
            queue.failed(anotherA, Duration.ofMinutes(2));
            assertTrue(queue.poll().isEmpty());
            assertFalse(queue.add(a));
            assertEquals(clock.instant().plusSeconds(1), queue.nextDueAt().orElseThrow());
            clock.advance(Duration.ofSeconds(1));
            assertEquals(a, queue.poll().orElseThrow());
            queue.failed(a, Duration.ofSeconds(30));
        }
        try (var queue = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            assertEquals(0, queue.dueCount());
            assertEquals(clock.instant().plusSeconds(30), queue.nextDueAt().orElseThrow());
        }
    }

    @Test void crashLeaseExpiresAfterFiveMinutesAndCancellationCanReleaseImmediately() throws Exception {
        var clock = new MutableClock();
        Path database = temp.resolve("frontier.db");
        URI page = uri("https://soict.hust.edu.vn/article");
        try (var queue = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            queue.add(page);
            assertEquals(page, queue.poll().orElseThrow());
            assertEquals(0, queue.dueCount());
            assertEquals(clock.instant().plus(Duration.ofMinutes(5)), queue.nextDueAt().orElseThrow());
        }
        try (var queue = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            assertTrue(queue.poll().isEmpty());
            clock.advance(Duration.ofMinutes(5).minusSeconds(1));
            assertEquals(0, queue.dueCount());
            clock.advance(Duration.ofSeconds(1));
            assertEquals(page, queue.poll().orElseThrow());
            queue.release(page);
            assertEquals(1, queue.dueCount());
            assertEquals(page, queue.poll().orElseThrow());
            queue.deferOrigin(page, clock.instant().plus(Duration.ofHours(1)));
            queue.release(page);
            assertEquals(0, queue.dueCount());
            assertEquals(1, queue.pendingCount());
        }
    }

    @Test void emptyFrontierAndInvalidConfigurationHavePredictableResults() throws Exception {
        var clock = new MutableClock();
        Path database = temp.resolve("frontier.db");
        assertThrows(IllegalArgumentException.class,
                () -> new PersistentCrawlFrontier(database, Duration.ofMillis(-1), clock));
        try (var queue = new PersistentCrawlFrontier(database, REVISIT, clock)) {
            assertEquals(0, queue.pendingCount());
            assertEquals(0, queue.dueCount());
            assertTrue(queue.poll().isEmpty());
            assertTrue(queue.nextDueAt().isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> queue.failed(uri("https://hust.edu.vn/"), Duration.ofSeconds(-1)));
            assertEquals(0, queue.addAll(List.of(uri("https://example.com/"))));
        }
    }

    private static URI uri(String value) { return URI.create(value); }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-07T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
