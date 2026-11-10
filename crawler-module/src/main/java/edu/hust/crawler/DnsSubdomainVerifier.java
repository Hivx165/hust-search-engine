package edu.hust.crawler;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.net.URI;

/** DNS evidence is separate from whether a hostname serves a usable HTTP website. */
public final class DnsSubdomainVerifier {
    private static final int MAX_WORKERS = 8;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private final Resolver resolver;
    private final int concurrency;

    @FunctionalInterface
    interface Resolver {
        Set<String> resolve(String hostname) throws Exception;
    }

    public record Entry(String hostname, String status, Set<String> addresses) {
        public Entry {
            addresses = Collections.unmodifiableSet(new TreeSet<>(addresses));
        }
    }

    public DnsSubdomainVerifier() {
        this(DnsSubdomainVerifier::resolveDns, MAX_WORKERS);
    }

    DnsSubdomainVerifier(Resolver resolver, int concurrency) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (concurrency < 1) throw new IllegalArgumentException("DNS concurrency must be positive");
        this.concurrency = Math.min(concurrency, MAX_WORKERS);
    }

    /** Invalid/out-of-scope names are ignored; source and DNS failures are never called NXDOMAIN. */
    public List<Entry> verify(Collection<String> hosts) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("DNS verification interrupted");
        Set<String> normalized = new TreeSet<>();
        for (String host : hosts) normalize(host).ifPresent(normalized::add);
        if (normalized.isEmpty()) return List.of();

        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, normalized.size()), task -> {
            Thread thread = new Thread(task, "hust-dns-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        CompletionService<Entry> completion = new ExecutorCompletionService<>(executor);
        List<Future<Entry>> tasks = new ArrayList<>();
        try {
            for (String host : normalized) tasks.add(completion.submit(() -> verifyOne(host)));
            List<Entry> results = new ArrayList<>(normalized.size());
            for (int i = 0; i < normalized.size(); i++) {
                try {
                    results.add(completion.take().get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof InterruptedException interrupted) throw interrupted;
                    throw new IllegalStateException("DNS verification worker failed", e.getCause());
                }
            }
            results.sort(Comparator.comparing(Entry::hostname));
            return List.copyOf(results);
        } finally {
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Entry verifyOne(String host) throws InterruptedException {
        try {
            Set<String> answer = resolver.resolve(host);
            Set<String> addresses = new TreeSet<>();
            if (answer != null) {
                for (String address : answer)
                    if (address != null && isAddress(address.trim())) addresses.add(address.trim());
            }
            return new Entry(host, addresses.isEmpty() ? "UNRESOLVED" : "RESOLVED", addresses);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            return new Entry(host, "UNRESOLVED", Set.of());
        }
    }

    private static boolean isAddress(String value) {
        if (value.contains(":")) {
            try { return URI.create("http://[" + value + "]/").getHost() != null; }
            catch (IllegalArgumentException e) { return false; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static Optional<String> normalize(String value) {
        if (value == null) return Optional.empty();
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.length() > 253 || !(host.equals("hust.edu.vn") || host.endsWith(".hust.edu.vn")))
            return Optional.empty();
        for (String label : host.split("\\.", -1)) if (!LABEL.matcher(label).matches()) return Optional.empty();
        return Optional.of(host);
    }

    private static Set<String> resolveDns(String hostname) throws Exception {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "1500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext context = new InitialDirContext(environment);
        try {
            Set<String> addresses = new TreeSet<>();
            for (String type : List.of("A", "AAAA")) {
                try {
                    if (Thread.interrupted()) throw new InterruptedException("DNS lookup interrupted");
                    // Request each record type separately. A multi-type JNDI query uses DNS ANY,
                    // which many resolvers intentionally answer without the requested addresses.
                    Attributes attributes = context.getAttributes(hostname + ".", new String[]{type});
                    Attribute attribute = attributes.get(type);
                    if (attribute == null) continue;
                    NamingEnumeration<?> values = attribute.getAll();
                    try {
                        while (values.hasMore()) {
                            String address = String.valueOf(values.next()).trim();
                            if (!address.isEmpty()) addresses.add(address);
                        }
                    } finally {
                        values.close();
                    }
                } catch (NamingException ignored) { /* A failure must not discard a working AAAA, or vice versa. */ }
            }
            return addresses;
        } finally {
            context.close();
        }
    }
}
