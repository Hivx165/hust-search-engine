package edu.hust.crawler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PassiveDiscoveryRunnerTest {
    @TempDir Path temp;

    @Test void validatesCandidatesPreservesWorkingNavigationAndRetainsCacheDuringSourceOutage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean outage = new AtomicBoolean();
        server.createContext("/", exchange -> {
            String text = exchange.getRequestURI().getPath().equals("/crt")
                    ? "[{\"name_value\":\"DEEP.hust.edu.vn\\nmissing.hust.edu.vn\\n*.dept.hust.edu.vn\"}]" : "[]";
            int code = outage.get() ? 503 : 200;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var source = new CertificateTransparencyDiscovery(HttpClient.newHttpClient(), URI.create(base + "/crt"),
                    URI.create(base + "/certspotter"), 5, 0);
            var dnsQueries = new java.util.concurrent.atomic.AtomicInteger();
            var dns = new DnsSubdomainVerifier(hostname -> {
                dnsQueries.incrementAndGet();
                if (hostname.equals("deep.hust.edu.vn")) return Set.of("203.0.113.5");
                throw new IOException("fixture unresolved");
            }, 2);
            Path output = temp.resolve("initial_subdomains.txt");
            Files.writeString(output, "# Existing seed\nhttp://deep.hust.edu.vn/directory\n");
            var registry = new SubdomainStore(temp, output);
            registry.rememberFetched(URI.create("http://deep.hust.edu.vn/directory"));

            var runner = new PassiveDiscoveryRunner(source, dns);
            var report = runner.discover(temp, output);
            assertEquals(2, report.reportedHosts());
            assertEquals(1, report.dnsResolvedHosts());
            assertEquals(1, report.wildcardPatterns());
            assertTrue(report.sourceComplete());
            assertTrue(new SubdomainStore(temp, output).entryPoints().contains(URI.create("http://deep.hust.edu.vn/directory")));
            String seeds = Files.readString(output);
            assertTrue(seeds.contains("http://deep.hust.edu.vn/directory"));
            assertFalse(seeds.contains("missing.hust.edu.vn"));
            assertFalse(seeds.contains("*.dept.hust.edu.vn"));
            String csv = Files.readString(temp.resolve("passive-discovery/dns_status.csv"));
            assertTrue(csv.contains("deep.hust.edu.vn,RESOLVED,203.0.113.5"));
            assertTrue(csv.contains("missing.hust.edu.vn,UNRESOLVED,"));
            assertEquals(2, dnsQueries.get());

            // A failing source must not erase discovered candidates or count invalid cached input.
            Files.writeString(temp.resolve("passive-discovery/candidates.txt"),
                    "\nhttps://bad.hust.edu.vn/path\noutside.example\n", StandardOpenOption.APPEND);
            outage.set(true);
            var cached = runner.discover(temp, output);
            assertEquals(2, cached.reportedHosts());
            assertEquals(1, cached.dnsResolvedHosts());
            assertFalse(cached.sourceComplete());
            assertFalse(cached.sourceErrors().isEmpty());
            assertEquals(2, dnsQueries.get(), "Fresh DNS evidence must be reused between crawl rounds");
            assertEquals(Set.of("deep.hust.edu.vn", "missing.hust.edu.vn"),
                    new HashSet<>(Files.readAllLines(temp.resolve("passive-discovery/candidates.txt"))));
        } finally { server.stop(0); }
    }
}
