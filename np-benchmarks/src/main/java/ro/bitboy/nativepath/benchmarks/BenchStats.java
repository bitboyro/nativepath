package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpServer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/**
 * The {@code /__stats} body served by the benchmark servers: bytes allocated on the JVM heap so far
 * (all threads) and, on the NativePath engine, its request timings. server_layers.sh reads it
 * before and after each measurement and divides the difference by the requests served. The JVM's own
 * memory figures (heap used and committed, non-heap, collections, live threads) ride along for the
 * soak sampler.
 */
final class BenchStats {

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    static String json(NativePathHttpServer server) {
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        var heap = MEMORY.getHeapMemoryUsage();
        StringBuilder sb = new StringBuilder("{\"alloc_bytes\":").append(THREADS.getTotalThreadAllocatedBytes())
                .append(",\"heap_used_mb\":").append(heap.getUsed() >> 20)
                .append(",\"heap_committed_mb\":").append(heap.getCommitted() >> 20)
                .append(",\"nonheap_mb\":").append(MEMORY.getNonHeapMemoryUsage().getUsed() >> 20)
                .append(",\"gc_count\":").append(gcCount).append(",\"gc_ms\":").append(gcMs)
                .append(",\"jvm_threads\":").append(THREADS.getThreadCount());
        if (server != null) {
            NativePathHttpServer.EngineStats e = server.engineStats();
            sb.append(",\"engine\":[").append(e.requests()).append(',').append(e.readNanos()).append(',')
                    .append(e.queueNanos()).append(',').append(e.handlerNanos()).append(',')
                    .append(e.respondNanos()).append(',').append(e.replyNanos()).append(',')
                    .append(e.shedRequests()).append(',').append(e.shedConnections()).append(',')
                    .append(e.stolen()).append(']');
        }
        return sb.append('}').toString();
    }

    private BenchStats() {}
}
