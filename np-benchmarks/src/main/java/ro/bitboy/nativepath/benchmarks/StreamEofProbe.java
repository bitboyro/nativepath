package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathStream;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;

/** Diagnostic: stream read-until-EOF vs stop-at-declared-length, against the JDK test server. */
public class StreamEofProbe {
    public static void main(String[] args) throws Exception {
        for (int size : new int[] { 256, 8192, 65536 }) {
            byte[] payload = new byte[size];
            Arrays.fill(payload, (byte) 'A');
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/p", ex -> {
                ex.sendResponseHeaders(200, payload.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(payload); }
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/p";
            try (NativePathHttpClient c = NativePathHttpClient.create()) {
                byte[] buf = new byte[65536];
                for (boolean untilEof : new boolean[] { true, false, true, false }) {
                    int n = 400;
                    long worst = 0, t0 = System.nanoTime();
                    for (int i = 0; i < n; i++) {
                        long t = System.nanoTime();
                        try (NativePathStream s = c.getStream(url)) {
                            long got = 0;
                            int r;
                            while (untilEof || got < size) {
                                r = s.read(buf, 0, buf.length);
                                if (r <= 0) break;
                                got += r;
                            }
                        }
                        worst = Math.max(worst, System.nanoTime() - t);
                    }
                    System.out.printf("%6d B  %-16s avg %8.1f us   worst %9.1f us%n", size,
                            untilEof ? "read until EOF" : "stop at length",
                            (System.nanoTime() - t0) / 1e3 / n, worst / 1e3);
                }
            }
            server.stop(0);
        }
    }
}
