package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathRequest;
import ro.bitboy.nativepath.NativePathStream;
import ro.bitboy.nativepath.NativeSecret;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Per-request cost of connection isolation, against the external Python server (separate process,
 * so the server does not share the JVM). Sequential requests, 3 rounds each, median reported.
 * Keep N below the ephemeral-port limit (about 16k on macOS): isolated requests leave sockets in TIME_WAIT.
 */
public class IsolationCostProbe {
    private static final int N = 1500;

    public static void main(String[] args) throws Exception {
        int port = 49997;
        Process server = new ProcessBuilder("python3", "np-benchmarks/secret_server.py", String.valueOf(port))
                .redirectErrorStream(true).start();
        if (!"READY".equals(new BufferedReader(new InputStreamReader(server.getInputStream())).readLine()))
            throw new IllegalStateException("server did not start");
        String url = "http://127.0.0.1:" + port + "/plain";
        try {
            System.out.printf("%-48s %12s%n", "request kind (average, microseconds)", "per request");
            run("pooled, no secret header", url, false, false);
            run("secret header (own connection per request)", url, true, false);
            run("strict isolation, no secret header", url, false, true);
        } finally {
            server.destroy();
        }
        System.exit(0);
    }

    private static void run(String label, String url, boolean secretHeader, boolean isolate) {
        try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).isolateConnections(isolate).build();
             NativeSecret token = NativeSecret.allocate(16)) {
            for (int i = 0; i < 16; i++) token.asMemorySegment().set(ValueLayout.JAVA_BYTE, i, (byte) ('a' + i));
            double[] rounds = new double[3];
            for (int r = 0; r < 4; r++) {                       // round 0 is warmup
                long t0 = System.nanoTime();
                for (int i = 0; i < N; i++) {
                    try (NativePathRequest req = NativePathRequest.get(url)) {
                        if (secretHeader) req.secretHeader("Authorization", "Bearer ", token);
                        else req.header("X-Plain", "1");
                        try (NativePathStream s = c.getStream(req)) {
                            MemorySegment seg;
                            while ((seg = s.nextChunk()) != null) { /* consume in native memory */ }
                        }
                    }
                }
                if (r > 0) rounds[r - 1] = (System.nanoTime() - t0) / 1e3 / N;
            }
            java.util.Arrays.sort(rounds);
            System.out.printf("%-48s %10.1f us%n", label, rounds[1]);
        }
    }
}
