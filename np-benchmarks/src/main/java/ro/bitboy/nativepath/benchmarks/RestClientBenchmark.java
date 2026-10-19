package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.spring.NativePathClientHttpRequestFactory;
import com.sun.management.ThreadMXBean;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.LongSummaryStatistics;

/**
 * Multi-payload benchmark:
 *   1. Spring RestClient (standard JDK factory)
 *   2. Spring RestClient (NativePath factory — single @Bean swap)
 *
 * Measures across 4 payload sizes: 256B / 8KB / 64KB / 512KB
 * Reports: avg latency, P99, heap bytes allocated per request, GC count, GC pause time.
 */
public class RestClientBenchmark {

    private static final int WARMUP     = 100;
    private static final int ITERATIONS = 300;

    // Payload sizes to stress-test the allocation difference
    private static final int[] PAYLOAD_SIZES = { 256, 8_192, 65_536, 524_288 };

    private static final ThreadMXBean THREAD_MX =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS =
            ManagementFactory.getGarbageCollectorMXBeans();

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================================");
        System.out.println("   SPRING RESTCLIENT BENCHMARK (Latency + Memory Allocation)     ");
        System.out.println("   Standard JDK Factory  vs  NativePath Factory                 ");
        System.out.println("=================================================================\n");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Dynamic payload: server reads content-size from query param
        server.createContext("/api/payload", exchange -> {
            String query = exchange.getRequestURI().getQuery(); // e.g. size=8192
            int size = 256;
            if (query != null && query.startsWith("size=")) {
                size = Integer.parseInt(query.substring(5));
            }
            byte[] payload = buildPayload(size);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/payload";
        System.out.println("[*] Server: " + baseUrl);
        System.out.println("[*] Warmup: " + WARMUP + " | Iterations: " + ITERATIONS + "\n");

        try (NativePathClientHttpRequestFactory nativePathFactory = NativePathClientHttpRequestFactory.builder().build()) {

            RestClient stdClient = RestClient.builder()
                    .requestFactory(new JdkClientHttpRequestFactory())
                    .build();
            RestClient encClient = RestClient.builder()
                    .requestFactory(nativePathFactory)
                    .build();

            java.net.http.HttpClient javaClient = java.net.http.HttpClient.newHttpClient();

            // Warmup all clients
            System.out.println("--- Warming up both clients ---");
            String warmUrl = baseUrl + "?size=256";
            for (int i = 0; i < WARMUP; i++) stdClient.get().uri(warmUrl).retrieve().body(byte[].class);
            for (int i = 0; i < WARMUP; i++) encClient.get().uri(warmUrl).retrieve().body(byte[].class);
            for (int i = 0; i < WARMUP; i++) javaGet(javaClient, warmUrl);
            System.out.println("[*] Warmup complete.\n");

            for (int payloadSize : PAYLOAD_SIZES) {
                String url = baseUrl + "?size=" + payloadSize;
                System.out.printf("=== Payload Size: %s ===%n", humanSize(payloadSize));

                // ---- Standard RestClient ----
                Result stdResult = measure(() -> stdClient.get().uri(url).retrieve().body(byte[].class));
                // ---- Raw java.net.http.HttpClient ----
                Result javaResult = measure(() -> javaGet(javaClient, url));
                // ---- NativePath RestClient ----
                Result encResult = measure(() -> encClient.get().uri(url).retrieve().body(byte[].class));

                printResults(stdResult, javaResult, encResult, payloadSize);
            }

            server.stop(0);
        }
    }

    private interface Call { void run() throws Exception; }

    private static void javaGet(java.net.http.HttpClient c, String url) throws Exception {
        c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private static Result measure(Call call) throws Exception {
        long[] latencies  = new long[ITERATIONS];
        long[] allocBytes = new long[ITERATIONS];
        long threadId = Thread.currentThread().threadId();

        long gcCountBefore = totalGcCount();
        long gcTimeBefore  = totalGcTime();
        long wallStart     = System.nanoTime();

        for (int i = 0; i < ITERATIONS; i++) {
            long allocBefore = THREAD_MX.getThreadAllocatedBytes(threadId);
            long t0          = System.nanoTime();

            call.run();

            latencies[i]  = System.nanoTime() - t0;
            allocBytes[i] = THREAD_MX.getThreadAllocatedBytes(threadId) - allocBefore;
        }

        long wallNs = System.nanoTime() - wallStart;
        long gcCount = totalGcCount() - gcCountBefore;
        long gcTimeMs = totalGcTime() - gcTimeBefore;

        return new Result(latencies, allocBytes, wallNs, gcCount, gcTimeMs);
    }

    private static void printResults(Result std, Result jdk, Result enc, int payloadSize) {
        Result[] rs = { std, jdk, enc };
        System.out.printf("  %-24s  %14s  %14s  %14s%n", "Metric", "RestClient/JDK", "java HttpClient", "NativePath");
        System.out.println("  " + "-".repeat(72));
        row("Avg Latency", rs, r -> String.format("%.3f ms", Arrays.stream(r.latencies).average().orElse(0) / 1e6));
        row("P99 Latency", rs, r -> String.format("%.3f ms", percentile(r.latencies, 99) / 1e6));
        row("Throughput", rs, r -> String.format("%.0f/s", ITERATIONS / (r.wallNs / 1e9)));
        row("Heap Alloc / request", rs, r -> humanSize((long) Arrays.stream(r.allocBytes).average().orElse(0)));
        row("GC Collections", rs, r -> String.valueOf(r.gcCount));
        row("GC Pause Time", rs, r -> r.gcTimeMs + " ms");
        System.out.println();
    }

    private static void row(String name, Result[] rs, java.util.function.Function<Result, String> f) {
        System.out.printf("  %-24s  %14s  %14s  %14s%n", name, f.apply(rs[0]), f.apply(rs[1]), f.apply(rs[2]));
    }

    // ---- Helpers ----

    private static byte[] buildPayload(int size) {
        // Build a realistic-ish JSON-like response body
        StringBuilder sb = new StringBuilder("{\"data\":\"");
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        while (sb.length() < size - 12) {
            sb.append(chars.charAt((int)(Math.random() * chars.length())));
        }
        sb.append("\"}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long percentile(long[] data, double pct) {
        long[] s = Arrays.copyOf(data, data.length);
        Arrays.sort(s);
        int idx = (int) Math.ceil((pct / 100.0) * s.length) - 1;
        return s[Math.max(0, Math.min(idx, s.length - 1))];
    }

    private static long totalGcCount() {
        return GC_BEANS.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    private static long totalGcTime() {
        return GC_BEANS.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1_024)       return bytes + " B";
        if (bytes < 1_048_576)   return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / 1_048_576.0);
    }

    private record Result(long[] latencies, long[] allocBytes, long wallNs, long gcCount, long gcTimeMs) {}
}
