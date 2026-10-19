package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.spring.NativePathClientHttpRequestFactory;
import com.sun.net.httpserver.HttpServer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * JMH counterpart of {@link RestClientBenchmark}: RestClient (JDK factory) vs
 * raw java.net.http.HttpClient vs RestClient (NativePath factory).
 *
 * Run: java --enable-native-access=ALL-UNNAMED -cp target/classes:&lt;deps&gt; ro.bitboy.nativepath.benchmarks.JmhClientBenchmark
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@State(Scope.Benchmark)
public class JmhClientBenchmark {

    @Param({"256", "8192", "65536", "524288"})
    public int payloadSize;

    private HttpServer server;
    private String url;
    private RestClient restJdk;
    private RestClient restNativePath;
    private HttpClient javaClient;
    private NativePathClientHttpRequestFactory nativePathFactory;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        byte[] payload = new byte[payloadSize];
        Arrays.fill(payload, (byte) 'A');
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/payload", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/payload";

        restJdk = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();
        nativePathFactory = NativePathClientHttpRequestFactory.builder().build();
        restNativePath = RestClient.builder().requestFactory(nativePathFactory).build();
        javaClient = HttpClient.newHttpClient();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        nativePathFactory.close();
        server.stop(0);
    }

    @Benchmark
    public void restClientJdk(Blackhole bh) {
        bh.consume(restJdk.get().uri(url).retrieve().body(byte[].class));
    }

    @Benchmark
    public void javaHttpClient(Blackhole bh) throws Exception {
        bh.consume(javaClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body());
    }

    @Benchmark
    public void restClientNativePath(Blackhole bh) {
        bh.consume(restNativePath.get().uri(url).retrieve().body(byte[].class));
    }

    public static void main(String[] args) throws Exception {
        Options opts = new OptionsBuilder()
                .include(JmhClientBenchmark.class.getSimpleName())
                .addProfiler("gc")
                .build();
        new Runner(opts).run();
    }
}
