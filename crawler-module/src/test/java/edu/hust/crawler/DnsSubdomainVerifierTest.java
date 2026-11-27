package edu.hust.crawler;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DnsSubdomainVerifierTest {
    @Test void normalizesNamesAndReturnsDeterministicImmutableEvidence() throws Exception {
        Set<String> queried = ConcurrentHashMap.newKeySet();
        var verifier = new DnsSubdomainVerifier(host -> {
            queried.add(host);
            if (host.startsWith("a.")) return Set.of("2001:db8::1", "192.0.2.1");
            if (host.startsWith("b.")) throw new IOException("DNS unavailable");
            if (host.startsWith("c.")) return Set.of("alias.hust.edu.vn", "999.0.0.1", "bad:address");
            return null;
        }, 3);
        var result = verifier.verify(Arrays.asList(" D.HUST.EDU.VN. ", "c.hust.edu.vn", "B.hust.edu.vn",
                "a.hust.edu.vn", "A.HUST.EDU.VN.", "hust.edu.vn.evil.com", "evilhust.edu.vn",
                "*.hust.edu.vn", "bad_label.hust.edu.vn", "-bad.hust.edu.vn", "bad-.hust.edu.vn",
                "https://a.hust.edu.vn/", "a..hust.edu.vn", "a.hust.edu.vn..", "x".repeat(64) + ".hust.edu.vn", null));

        assertEquals(List.of("a.hust.edu.vn", "b.hust.edu.vn", "c.hust.edu.vn", "d.hust.edu.vn"),
                result.stream().map(DnsSubdomainVerifier.Entry::hostname).toList());
        assertEquals(Set.of("a.hust.edu.vn", "b.hust.edu.vn", "c.hust.edu.vn", "d.hust.edu.vn"), queried);
        assertEquals("RESOLVED", result.get(0).status());
        assertEquals(List.of("192.0.2.1", "2001:db8::1"), new ArrayList<>(result.get(0).addresses()));
        for (var entry : result.subList(1, result.size())) {
            assertEquals("UNRESOLVED", entry.status());
            assertTrue(entry.addresses().isEmpty());
        }
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> result.get(0).addresses().add("192.0.2.2"));
    }

    @Test void usesAtMostEightDaemonWorkers() throws Exception {
        var firstEightStarted = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var allDaemon = new AtomicBoolean(true);
        var failure = new AtomicReference<Throwable>();
        var result = new AtomicReference<List<DnsSubdomainVerifier.Entry>>();
        var verifier = new DnsSubdomainVerifier(host -> {
            allDaemon.compareAndSet(true, Thread.currentThread().isDaemon());
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            firstEightStarted.countDown();
            try { release.await(); return Set.of("192.0.2.1"); }
            finally { active.decrementAndGet(); }
        }, 100);
        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < 20; i++) hosts.add("host" + i + ".hust.edu.vn");
        Thread caller = new Thread(() -> {
            try { result.set(verifier.verify(hosts)); }
            catch (Throwable e) { failure.set(e); }
        });
        caller.start();
        try {
            assertTrue(firstEightStarted.await(3, TimeUnit.SECONDS));
            assertEquals(8, maximum.get());
        } finally {
            release.countDown();
            caller.join(3000);
            if (caller.isAlive()) caller.interrupt();
        }
        assertFalse(caller.isAlive());
        assertNull(failure.get());
        assertTrue(allDaemon.get());
        assertEquals(20, result.get().size());
    }

    @Test void callerInterruptionCancelsOutstandingLookups() throws Exception {
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var verifier = new DnsSubdomainVerifier(host -> {
            started.countDown();
            try { new CountDownLatch(1).await(); return Set.of(); }
            finally { stopped.countDown(); }
        }, 1);
        Thread caller = new Thread(() -> {
            try { verifier.verify(List.of("a.hust.edu.vn", "b.hust.edu.vn")); }
            catch (Throwable e) { failure.set(e); }
        });
        caller.start();
        try { assertTrue(started.await(3, TimeUnit.SECONDS)); }
        finally { caller.interrupt(); caller.join(3000); }
        assertFalse(caller.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
    }

    @Test void resolverInterruptionPropagatesAndEmptyInputMakesNoQueries() {
        var verifier = new DnsSubdomainVerifier(host -> { throw new InterruptedException("cancelled"); }, 1);
        assertThrows(InterruptedException.class, () -> verifier.verify(List.of("a.hust.edu.vn")));
        assertDoesNotThrow(() -> assertTrue(verifier.verify(List.of()).isEmpty()));
        assertThrows(IllegalArgumentException.class, () -> new DnsSubdomainVerifier(host -> Set.of(), 0));
    }
}
