package edu.hust.crawler;

import java.net.URI;
import java.util.*;

/** Visit a newly seen origin before draining articles from origins already scheduled. */
final class CrawlFrontier {
    private final Deque<URI> newOrigins = new ArrayDeque<>(), pages = new ArrayDeque<>();
    private final Set<URI> queued = new HashSet<>(), origins = new HashSet<>();
    private final long capacity;
    CrawlFrontier(long capacity) { this.capacity = capacity; }
    void add(URI uri) {
        if (queued.contains(uri)) return;
        URI origin = SubdomainDiscoverer.origin(uri);
        // New origins must not be crowded out by a large list of same-site article URLs.
        if (queued.size() >= capacity && origins.contains(origin)) return;
        queued.add(uri);
        if (origins.add(origin)) newOrigins.addLast(uri);
        else pages.addLast(uri);
    }
    boolean isEmpty() { return newOrigins.isEmpty() && pages.isEmpty(); }
    URI next() { return newOrigins.isEmpty() ? pages.removeFirst() : newOrigins.removeFirst(); }
}
