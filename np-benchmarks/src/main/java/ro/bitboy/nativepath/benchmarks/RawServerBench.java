package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpServer;
import ro.bitboy.nativepath.ServerExchange;
import ro.bitboy.nativepath.ServerLane;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The engine with plain Java workers and no Spring: one worker per lane polls and answers "hello"
 * (and {@code /payload?size=N}, and {@code /echo} and {@code /json} with the request body).
 * Measures what crossing into the JVM costs on top of the engine.
 *
 *   java ... RawServerBench &lt;port&gt; [lanes=0 (one per core)]
 *
 * Engine settings come from the environment, named as the Spring properties are
 * ({@code NATIVEPATH_SERVER_BATCH}, {@code _SPIN}, {@code _WORK_STEALING}, {@code _MAX_QUEUED},
 * {@code _MAX_CONNECTIONS}).
 */
public class RawServerBench {
    private static int setting(String name, int dflt) {
        String v = System.getenv("NATIVEPATH_SERVER_" + name);
        return v == null ? dflt : Integer.parseInt(v);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18091;
        int lanes = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        byte[] hello = "hello".getBytes(StandardCharsets.US_ASCII);
        Map<String, List<String>> headers = Map.of("content-type", List.of("text/plain"));
        Map<String, List<String>> json = Map.of("content-type", List.of("application/json"));
        NativePathHttpServer.Builder builder = NativePathHttpServer.builder().port(port).lanes(lanes)
                .batch(setting("BATCH", 1)).spin(setting("SPIN", 0))
                .workStealing(Boolean.parseBoolean(System.getenv("NATIVEPATH_SERVER_WORK_STEALING")));
        // The limits must be positive; unset (0) keeps the engine default.
        if (setting("MAX_QUEUED", 0) > 0) builder.maxQueued(setting("MAX_QUEUED", 0));
        if (setting("MAX_CONNECTIONS", 0) > 0) builder.maxConnections(setting("MAX_CONNECTIONS", 0));
        NativePathHttpServer server = builder.start();
        System.out.println("READY " + server.port() + " lanes=" + server.lanes().size());
        for (ServerLane lane : server.lanes()) {
            Thread.ofPlatform().start(() -> {
                while (server.isRunning()) {
                    try (ServerExchange ex = lane.poll(200)) {
                        if (ex == null) continue;
                        if (ex.target().equals("/__stats")) {
                            ex.respond(200, json, BenchStats.json(server).getBytes(StandardCharsets.US_ASCII));
                        } else if (ex.target().startsWith("/payload")) {
                            int q = ex.target().indexOf("size=");
                            byte[] body = new byte[q < 0 ? 0 : Integer.parseInt(ex.target().substring(q + 5))];
                            java.util.Arrays.fill(body, (byte) 'x');
                            ex.respond(200, headers, body);
                        } else if (ex.target().startsWith("/echo") || ex.target().startsWith("/json")) {
                            // The body back, as the other raw servers do (no parsing).
                            byte[] in = ex.body().equals(java.lang.foreign.MemorySegment.NULL)
                                    ? new byte[0] : ex.body().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                            ex.respond(200, ex.target().startsWith("/json") ? json : headers, in);
                        } else {
                            ex.respond(200, headers, hello);
                        }
                    }
                }
            });
        }
        Thread.currentThread().join();
    }
}
