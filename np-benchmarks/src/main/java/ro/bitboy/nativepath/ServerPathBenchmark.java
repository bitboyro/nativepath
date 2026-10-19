package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The server's worker side with no network, step by step. Each request is synthetic (built by the
 * engine with typical browser headers) but goes through the real hand-over, {@link ServerExchange}
 * and {@code respond} code. Each benchmark adds one step to the one above it, so differences
 * between neighbouring rows price that step; run with {@code -prof gc} for allocation per step.
 *
 *   ffiNoop          a bare native call (the floor of any FFI step)
 *   a_takeAndClose   one native call fills the request view; build the exchange; close it unanswered
 *   b_threeLookups   + look up three headers (host, content-type, accept) as Spring does
 *   c_headerMap      + build the full header map instead (what iterating Spring's headers costs)
 *   d_respondSmall   + answer with a small JSON body
 *   e_respond64k     + answer with a 64 KB body (the one body copy into the engine)
 *
 * a_takeAndClose includes building the synthetic request in Rust, which the real path does in
 * the engine's I/O work instead; compare steps against a_takeAndClose, not against ffiNoop.
 *
 * In package ro.bitboy.nativepath because the exchange is created as a lane would (package-private).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@State(Scope.Thread)
public class ServerPathBenchmark {

    @Param({"0", "16384"})
    public int bodyBytes;

    private final MemorySegment view = Arena.ofAuto().allocate(ForeignBridge.REQUEST_VIEW);
    private final Map<String, List<String>> responseHeaders = Map.of("content-type", List.of("application/json"));
    private final byte[] small = "{\"id\":\"ord-42\",\"status\":\"accepted\"}".getBytes();
    private final byte[] big = new byte[65536];

    {
        Arrays.fill(big, (byte) 'A');
    }

    private ServerExchange take() {
        MemorySegment request = ForeignBridge.testServerRequest(0, bodyBytes, view);
        return new ServerExchange(request, view, Set.of());
    }

    @Benchmark
    public void ffiNoop() {
        ForeignBridge.testNoop();
    }

    @Benchmark
    public void a_takeAndClose(Blackhole bh) {
        try (ServerExchange ex = take()) {
            bh.consume(ex.target());
        }
    }

    @Benchmark
    public void b_threeLookups(Blackhole bh) {
        try (ServerExchange ex = take()) {
            bh.consume(ex.header("host"));
            bh.consume(ex.header("content-type"));
            bh.consume(ex.header("accept"));
        }
    }

    @Benchmark
    public void c_headerMap(Blackhole bh) {
        try (ServerExchange ex = take()) {
            bh.consume(ex.headers());
        }
    }

    @Benchmark
    public void d_respondSmall(Blackhole bh) {
        try (ServerExchange ex = take()) {
            bh.consume(ex.header("host"));
            bh.consume(ex.header("content-type"));
            bh.consume(ex.header("accept"));
            ex.respond(200, responseHeaders, small);
        }
    }

    @Benchmark
    public void e_respond64k(Blackhole bh) {
        try (ServerExchange ex = take()) {
            bh.consume(ex.header("host"));
            bh.consume(ex.header("content-type"));
            bh.consume(ex.header("accept"));
            ex.respond(200, responseHeaders, big);
        }
    }
}
