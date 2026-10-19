package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathResponse;
import ro.bitboy.nativepath.spring.NativePathClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The HTTP clients against one server that is not in this process: for each client, response size
 * and number of threads, the requests per second, latency percentiles, client CPU and heap
 * allocation per request. Every response is checked for its length. Results go to stdout and, as
 * {@code NPEV} lines, to stderr (which {@code np-bench} collects).
 *
 * <pre>
 * ClientBench &lt;base-url&gt; [clients] [sizes] [threads] [seconds] [rounds]
 *   base-url  a server with GET /payload?size=N (the benchmark servers)
 *   clients   jdk, rest_jdk, rest_nativepath, nativepath (comma separated; default all)
 *   sizes     response bytes, default 256,8192,65536,524288
 *   threads   default 1,16
 * </pre>
 *
 * Even rounds run the clients in reverse order, so no client always goes first. The JVM is shared by
 * all clients, so each is warmed up before it is measured.
 */
public final class ClientBench {

    interface Call {
        /** One GET of {@code url}; returns the body length. */
        int get(String url) throws Exception;
    }

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public static void main(String[] args) throws Exception {
        String base = args[0];
        List<String> clients = list(args, 1, "jdk,rest_jdk,rest_nativepath,nativepath");
        List<String> sizes = list(args, 2, "256,8192,65536,524288");
        List<String> threads = list(args, 3, "1,16");
        int seconds = args.length > 4 ? Integer.parseInt(args[4]) : 10;
        int rounds = args.length > 5 ? Integer.parseInt(args[5]) : 3;

        HttpClient jdk = HttpClient.newHttpClient();
        RestClient restJdk = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();
        NativePathClientHttpRequestFactory factory = NativePathClientHttpRequestFactory.builder().build();
        RestClient restNp = RestClient.builder().requestFactory(factory).build();
        NativePathHttpClient np = NativePathHttpClient.create();
        java.util.Map<String, Call> calls = java.util.Map.of(
                "jdk", url -> jdk.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body().length,
                "rest_jdk", url -> restJdk.get().uri(url).retrieve().body(byte[].class).length,
                "rest_nativepath", url -> restNp.get().uri(url).retrieve().body(byte[].class).length,
                "nativepath", url -> {
                    try (NativePathResponse r = np.get(url)) {
                        return (int) r.bodyAsSecret().asMemorySegment().byteSize();
                    }
                });

        for (int round = 1; round <= rounds; round++) {
            List<String> order = new ArrayList<>(clients);
            if (round % 2 == 0) java.util.Collections.reverse(order);
            for (String size : sizes) {
                for (String t : threads) {
                    for (String client : order) {
                        Call call = calls.get(client);
                        if (call == null) throw new IllegalArgumentException("unknown client " + client);
                        String url = base + "/payload?size=" + size;
                        measure(call, url, 2, Integer.parseInt(t), Integer.parseInt(size), null, 0, 0, null);  // warm-up
                        measure(call, url, seconds, Integer.parseInt(t), Integer.parseInt(size), client, round,
                                Integer.parseInt(size), size + "B_t" + t);
                    }
                }
            }
        }
        factory.close();
        np.close();
        System.exit(0);
    }

    private static List<String> list(String[] args, int i, String dflt) {
        String v = args.length > i && !args[i].isBlank() ? args[i] : dflt;
        return Arrays.asList(v.split("[ ,]+"));
    }

    /** Runs {@code threads} workers for {@code seconds}; reports when {@code client} is not null. */
    private static void measure(Call call, String url, int seconds, int threads, int expectLen, String client,
                                int round, int size, String scenario) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong done = new AtomicLong(), errors = new AtomicLong();
        long[][] lat = new long[threads][];
        Thread[] workers = new Thread[threads];
        long gc0 = 0, gcMs0 = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc0 += Math.max(0, g.getCollectionCount());
            gcMs0 += Math.max(0, g.getCollectionTime());
        }
        long alloc0 = THREADS.getTotalThreadAllocatedBytes();
        long cpu0 = OS.getProcessCpuTime();
        long t0 = System.nanoTime();
        for (int w = 0; w < threads; w++) {
            int id = w;
            workers[w] = Thread.ofPlatform().name("client-" + w).start(() -> {
                long[] mine = new long[1 << 16];
                int n = 0;
                while (!stop.get()) {
                    long s = System.nanoTime();
                    try {
                        if (call.get(url) != expectLen) errors.incrementAndGet();
                        else {
                            if (n == mine.length) mine = Arrays.copyOf(mine, n * 2);
                            mine[n++] = System.nanoTime() - s;
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    done.incrementAndGet();
                }
                lat[id] = Arrays.copyOf(mine, n);
            });
        }
        long last = 0;
        for (int sec = 1; sec <= seconds; sec++) {
            Thread.sleep(1000);
            long now = done.get();
            if (client != null) {
                event(String.format("{\"t\":%d,\"type\":\"tick\",\"exp\":\"client\",\"config\":\"%s\",\"scenario\":\"%s\",\"round\":%d,\"sec\":%d,\"phase\":\"measure\",\"rps\":%d,\"conns\":%d,\"errors\":%d,\"p50_us\":0,\"p99_us\":0}",
                        System.currentTimeMillis(), client, scenario, round, sec, now - last, threads, errors.get()));
            }
            last = now;
        }
        stop.set(true);
        for (Thread w : workers) w.join();
        double secs = (System.nanoTime() - t0) / 1e9;
        long cpu = OS.getProcessCpuTime() - cpu0;
        long alloc = THREADS.getTotalThreadAllocatedBytes() - alloc0;
        long gc = -gc0, gcMs = -gcMs0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc += Math.max(0, g.getCollectionCount());
            gcMs += Math.max(0, g.getCollectionTime());
        }
        if (client == null) return;

        long[] all = Arrays.stream(lat).flatMapToLong(Arrays::stream).sorted().toArray();
        long requests = done.get();
        double rps = all.length / secs;
        String result = String.format(java.util.Locale.ROOT,
                "{\"requests\":%d,\"errors\":%d,\"rps\":%.0f,\"p50_us\":%.0f,\"p99_us\":%.0f,\"p999_us\":%.0f,\"mb_per_s\":%.1f,\"shed\":0,\"gc_count\":%d,\"gc_ms\":%d}",
                all.length, errors.get(), rps, pct(all, .5), pct(all, .99), pct(all, .999), rps * size / 1e6, gc, gcMs);
        String m = String.format(java.util.Locale.ROOT,
                "{\"exp\":\"client\",\"config\":\"%s\",\"round\":%d,\"scenario\":\"%s\",\"connections\":%d,\"server_cpu_s\":%.3f,\"stats_before\":{\"alloc_bytes\":0},\"stats_after\":{\"alloc_bytes\":%d},\"result\":%s}",
                client, round, scenario, threads, cpu / 1e9, alloc, result);
        System.out.println(m);
        event("{\"t\":" + System.currentTimeMillis() + ",\"type\":\"measurement\"," + m.substring(1));
    }

    /** One whole line in one write: stdout is read by another process and must not land in the middle. */
    private static void event(String json) {
        System.err.print("NPEV " + json + "\n");
        System.err.flush();
    }

    private static double pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[(int) Math.min(sorted.length - 1, sorted.length * p)] / 1000.0;
    }

    private ClientBench() {}
}
