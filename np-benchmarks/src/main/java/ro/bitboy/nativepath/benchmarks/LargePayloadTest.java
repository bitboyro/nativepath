package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathResponse;
import ro.bitboy.nativepath.NativePathStream;
import ro.bitboy.nativepath.spring.NativePathClientHttpRequestFactory;
import com.sun.management.ThreadMXBean;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.CRC32;

/**
 * Large-payload test: streams a deterministic body of N MB and verifies it by CRC32.
 * Run each client in its own JVM with a small heap (-Xmx64m) so anything that buffers the
 * whole body on the heap fails, and compare peak process RSS and throughput.
 *
 * Usage: LargePayloadTest &lt;client&gt; &lt;sizeMb&gt; &lt;fixed|chunked&gt; [externalUrl]
 * With externalUrl the body comes from another process (it must serve the same repeating pattern).
 * clients: nativepath-stream, nativepath-zerocopy, nativepath-secret, nativepath-buffered, rest-nativepath, rest-jdk, jdk-stream
 */
public class LargePayloadTest {

    private static final int BLOCK = 64 * 1024;

    public static void main(String[] args) throws Exception {
        String client = args[0];
        long mb = Long.parseLong(args[1]);
        boolean chunked = args[2].equals("chunked");
        long total = mb * 1024 * 1024;

        byte[] block = new byte[BLOCK];
        for (int i = 0; i < BLOCK; i++) block[i] = (byte) (i * 31 + 7);
        CRC32 expected = new CRC32();
        for (long w = 0; w < total; w += BLOCK) expected.update(block, 0, BLOCK);

        String external = args.length > 3 ? args[3] : null;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, chunked ? 0 : total);
            try (OutputStream os = exchange.getResponseBody()) {
                for (long w = 0; w < total; w += BLOCK) os.write(block);
            } catch (java.io.IOException ignored) {
                // client closed early (expected for rejected requests)
            }
        });
        server.start();
        String url = external != null ? external : "http://127.0.0.1:" + server.getAddress().getPort() + "/big";

        long pid = ProcessHandle.current().pid();
        long baselineRss = rssKb(pid);
        long[] peak = { baselineRss };
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak[0] = Math.max(peak[0], rssKb(pid));
                try { Thread.sleep(25); } catch (InterruptedException e) { return; }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocBefore = allocated(mx);
        long t0 = System.nanoTime();
        String outcome;
        long crc = -1, bytes = 0;

        try {
            long[] res = run(client, url, total);
            bytes = res[0];
            crc = res[1];
            outcome = (bytes == total && crc == expected.getValue()) ? "OK (crc verified)" : "MISMATCH bytes=" + bytes;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            outcome = "FAILED: " + t.getClass().getSimpleName() + ": " + shorten(String.valueOf(root.getMessage() != null ? root.getMessage() : t.getMessage()));
        }

        double secs = (System.nanoTime() - t0) / 1e9;
        long allocMb = (allocated(mx) - allocBefore) / (1024 * 1024);
        sampler.interrupt();
        peak[0] = Math.max(peak[0], rssKb(pid));

        System.out.printf("%-17s %6d MB %-7s | %-52s | %6.2fs %7.0f MB/s | JVM alloc %6d MB | RSS %4d -> peak %4d MB%n",
                client, mb, chunked ? "chunked" : "fixed", outcome, secs, secs > 0 ? mb / secs : 0,
                allocMb, baselineRss / 1024, peak[0] / 1024);
        server.stop(0);
        System.exit(0);
    }

    /** Returns {bytesRead, crc}. */
    private static long[] run(String client, String url, long total) throws Exception {
        CRC32 crc = new CRC32();
        long[] n = { 0 };
        byte[] buf = new byte[BLOCK];
        switch (client) {
            case "nativepath-stream" -> {
                try (NativePathHttpClient c = NativePathHttpClient.create(); NativePathStream s = c.getStream(url)) {
                    int r;
                    while ((r = s.read(buf, 0, buf.length)) > 0) { crc.update(buf, 0, r); n[0] += r; }
                }
            }
            case "nativepath-zerocopy" -> {
                try (NativePathHttpClient c = NativePathHttpClient.create(); NativePathStream s = c.getStream(url)) {
                    MemorySegment seg;
                    long chunks = 0;
                    while ((seg = s.nextChunk()) != null) {
                        crc.update(seg.asByteBuffer());
                        n[0] += seg.byteSize();
                        chunks++;
                    }
                    System.err.printf("   [zerocopy] %d chunks, avg %d bytes%n", chunks, chunks == 0 ? 0 : n[0] / chunks);
                }
            }
            case "nativepath-secret" -> {
                // Secret mode: no heap copies possible, no connection reuse.
                try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build();
                     NativePathStream s = c.getStream(url)) {
                    MemorySegment seg;
                    while ((seg = s.nextChunk()) != null) {
                        crc.update(seg.asByteBuffer());
                        n[0] += seg.byteSize();
                    }
                }
            }
            case "nativepath-buffered" -> {
                try (NativePathHttpClient c = NativePathHttpClient.create(); NativePathResponse r = c.get(url)) {
                    MemorySegment seg = r.bodyAsSecret().asMemorySegment();
                    crc.update(seg.asByteBuffer());
                    n[0] = seg.byteSize();
                }
            }
            case "rest-nativepath" -> {
                try (NativePathClientHttpRequestFactory f = NativePathClientHttpRequestFactory.builder().build()) {
                    RestClient.builder().requestFactory(f).build().get().uri(url).exchange((req, res) -> {
                        readAll(res.getBody(), buf, crc, n);
                        return null;
                    });
                }
            }
            case "rest-jdk" -> {
                RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build()
                        .get().uri(url).exchange((req, res) -> {
                            readAll(res.getBody(), buf, crc, n);
                            return null;
                        });
            }
            case "jdk-stream" -> {
                HttpResponse<InputStream> res = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = res.body()) {
                    readAll(in, buf, crc, n);
                }
            }
            default -> throw new IllegalArgumentException("unknown client " + client);
        }
        return new long[] { n[0], crc.getValue() };
    }

    private static void readAll(InputStream in, byte[] buf, CRC32 crc, long[] n) throws java.io.IOException {
        int r;
        while ((r = in.read(buf, 0, buf.length)) > 0) { crc.update(buf, 0, r); n[0] += r; }
    }

    private static long allocated(ThreadMXBean mx) {
        long sum = 0;
        for (long id : mx.getAllThreadIds()) {
            long a = mx.getThreadAllocatedBytes(id);
            if (a > 0) sum += a;
        }
        return sum;
    }

    private static long rssKb(long pid) {
        try {
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid)).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Long.parseLong(out);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String shorten(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 48 ? s.substring(0, 48) + "…" : s;
    }
}
