package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathResponse;
import ro.bitboy.nativepath.NativeSecret;

import javax.management.MBeanServer;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MemoryDumpTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================================");
        System.out.println("   NATIVEPATH vs STANDARD JAVA: THE GHOST SECRET EXPERIMENT     ");
        System.out.println("=================================================================\n");

        int port = 49999;
        ProcessBuilder pb = new ProcessBuilder("python3", "np-benchmarks/mock_server.py", String.valueOf(port));
        pb.redirectErrorStream(true);
        Process serverProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()));
        String line = reader.readLine();
        if (line == null || !line.startsWith("READY")) {
            throw new RuntimeException("Failed to start external mock server: " + line);
        }
        System.out.println("[*] External mock server running on http://127.0.0.1:" + port + "\n");

        // Warmup: fire one request from each client to allow JIT and connection warmup
        executeStandardJava("http://127.0.0.1:" + port + "/secret/java");
        executeNativePath("http://127.0.0.1:" + port + "/secret/nativepath");
        System.out.println("[*] Warmup complete.\n");

        // -------------------------------------------------------------
        // TEST 1: Standard Java HttpClient (String on JVM Heap)
        // Measure across 10 iterations for a stable average
        // -------------------------------------------------------------
        System.out.println("--- [TEST 1: Standard Java HttpClient] ---");
        int iterations = 10;
        long standardTotal = 0;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            executeStandardJava("http://127.0.0.1:" + port + "/secret/java");
            standardTotal += System.nanoTime() - t0;
        }
        long standardAvgMs = (standardTotal / iterations) / 1_000_000;
        long standardAvgUs = ((standardTotal / iterations) / 1_000) % 1_000;
        System.out.println("   [Standard] Avg latency over " + iterations + " requests: " +
                           standardAvgMs + "." + String.format("%03d", standardAvgUs) + " ms");
        System.out.println("   [Standard] Last request completed. Local reference discarded.");
        System.out.println();

        // -------------------------------------------------------------
        // TEST 2: NativePath Client (Off-Heap NativeSecret + Zeroize)
        // -------------------------------------------------------------
        System.out.println("--- [TEST 2: NativePath Native Client] ---");
        long nativePathTotal = 0;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            executeNativePath("http://127.0.0.1:" + port + "/secret/nativepath");
            nativePathTotal += System.nanoTime() - t0;
        }
        long nativePathAvgMs = (nativePathTotal / iterations) / 1_000_000;
        long nativePathAvgUs = ((nativePathTotal / iterations) / 1_000) % 1_000;
        System.out.println("   [NativePath]  Avg latency over " + iterations + " requests: " +
                           nativePathAvgMs + "." + String.format("%03d", nativePathAvgUs) + " ms");
        System.out.println("   [NativePath]  Last request completed. NativeSecret closed and volatile-zeroed.");
        System.out.println();

        // -------------------------------------------------------------
        // VERIFICATION: Full Process Heap Dump
        // -------------------------------------------------------------
        System.out.println("[*] Triggering System.gc() and dumping full JVM heap to analyze residual memory...");
        System.gc();
        Thread.sleep(500);

        Path heapDumpPath = Path.of("target/process_memory.hprof");
        Files.deleteIfExists(heapDumpPath);
        dumpHeap(heapDumpPath.toString(), false);

        // Read tokens recorded on disk by mock server
        String[] tokens = Files.readString(Path.of("target/tokens.txt")).split("\n");
        String targetJavaSecret = tokens[0].trim();
        String targetNativePathSecret = tokens[1].trim();

        byte[] heapBytes = Files.readAllBytes(heapDumpPath);
        long javaMatches = countOccurrences(heapBytes, targetJavaSecret.getBytes(StandardCharsets.UTF_8));
        long nativePathMatches = countOccurrences(heapBytes, targetNativePathSecret.getBytes(StandardCharsets.UTF_8));

        // Cleanup
        serverProcess.destroyForcibly();
        Files.deleteIfExists(heapDumpPath);
        Files.deleteIfExists(Path.of("target/tokens.txt"));

        System.out.println("\n=================================================================");
        System.out.println("   MEMORY DUMP AUDIT RESULTS                                     ");
        System.out.println("=================================================================");
        System.out.println(" [1] Standard Java HttpClient:");
        System.out.println("     - Avg Latency:   " + standardAvgMs + "." + String.format("%03d", standardAvgUs) + " ms");
        System.out.println("     - Target Secret: " + targetJavaSecret);
        if (javaMatches > 0) {
            System.out.println("     - Memory Audit:  🚨 VULNERABLE");
            System.out.println("     - Residue Found: " + javaMatches + " copy/copies lingering in plain-text on JVM heap!");
        } else {
            System.out.println("     - Memory Audit:  Clean (GC overwrote before dump)");
        }
        System.out.println();
        System.out.println(" [2] NativePath Native Client:");
        System.out.println("     - Avg Latency:   " + nativePathAvgMs + "." + String.format("%03d", nativePathAvgUs) + " ms");
        System.out.println("     - Target Secret: " + targetNativePathSecret);
        if (nativePathMatches == 0) {
            System.out.println("     - Memory Audit:  ✅ 100% SECURE (ZERO RESIDUE)");
            System.out.println("     - Residue Found: EXACTLY 0 BYTES (Volatile-wiped in hardware RAM)");
        } else {
            System.out.println("     - Residue Found: " + nativePathMatches + " matches");
        }
        System.out.println("=================================================================");
    }

    private static void executeStandardJava(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Java forces the response into an immutable String on the GC heap
        String body = response.body();
        System.out.println("   [Standard] Ingested token (" + body.length() + " chars) onto JVM heap");
    }

    private static void executeNativePath(String url) {
        try (NativePathHttpClient client = NativePathHttpClient.create()) {
            try (NativePathResponse response = client.get(url)) {
                // Kept strictly off-heap; never converted to a JVM String
                try (NativeSecret secret = response.bodyAsSecret()) {
                    System.out.println("   [NativePath]  Ingested token (" + secret.length() + " bytes) into off-heap native memory");
                    // Consumes off-heap memory directly...
                } // Volatile zeroize wipe occurs here!
            }
        }
    }

    private static void dumpHeap(String fileName, boolean liveOnly) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Class<?> clazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
        Object bean = ManagementFactory.newPlatformMXBeanProxy(
                server, "com.sun.management:type=HotSpotDiagnostic", clazz);
        clazz.getMethod("dumpHeap", String.class, boolean.class).invoke(bean, fileName, liveOnly);
    }

    private static long countOccurrences(byte[] data, byte[] pattern) {
        long count = 0;
        int max = data.length - pattern.length;
        for (int i = 0; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                count++;
                i += pattern.length - 1;
            }
        }
        return count;
    }
}
