package ro.bitboy.nativepath.benchmarks;

import io.netty.channel.Channel;
import java.util.concurrent.atomic.AtomicInteger;

/** Connection policy shared by the capacity benchmark servers. Unset leaves normal runs alone. */
final class CapacityConnections {
    private static final int LIMIT = Integer.parseInt(
            System.getenv().getOrDefault("BENCH_CAPACITY_MAX_CONNECTIONS", "0"));
    static int limit() { return LIMIT; }
    private final AtomicInteger open = new AtomicInteger();
    void accept(Channel channel) {
        int count = open.incrementAndGet();
        channel.closeFuture().addListener(f -> open.decrementAndGet());
        if (limit() > 0 && count > limit()) channel.close();
    }
    static String json(String server, int limit) {
        return "{\"server\":\"" + server + "\",\"max_connections\":" + limit + "}";
    }
}
