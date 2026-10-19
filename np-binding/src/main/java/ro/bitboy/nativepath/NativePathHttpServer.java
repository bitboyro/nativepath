package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An HTTP/1.1 server run by the native NativePath engine.
 *
 * <p>The engine is split into {@linkplain ServerLane lanes}, like Netty's event loops: each
 * connection belongs to one lane, and one worker thread drives each lane by calling
 * {@link ServerLane#poll(long)}. That call runs the lane's event loop on the worker's own thread
 * until a request is ready, so a request never crosses threads. The engine reads each request
 * (head and body) into wiped native memory; nothing is pushed into the JVM. A request whose
 * exchange is closed without a response gets 500.
 *
 * <pre>{@code
 * try (NativePathHttpServer server = NativePathHttpServer.builder().port(8080).start()) {
 *     for (ServerLane lane : server.lanes()) {
 *         Thread.ofPlatform().start(() -> {
 *             while (server.isRunning()) {
 *                 try (ServerExchange ex = lane.poll(200)) {
 *                     if (ex != null) ex.respond(200, Map.of(), "hello".getBytes());
 *                 }
 *             }
 *         });
 *     }
 *     ...
 * }
 * }</pre>
 *
 * <p>While a worker handles a request, the other connections of its lane wait: handlers should
 * not block for long (as on Netty's event loops).
 */
public final class NativePathHttpServer implements AutoCloseable {

    final MemorySegment handle;
    private final int port;
    private final boolean tls;
    final java.util.Set<String> secretHeaders;
    private final List<ServerLane> lanes;
    // Readers are threads inside a lane's poll(); close() takes the write lock so the native server
    // is never freed while a thread is still driving one of its lanes.
    final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    volatile boolean running = true;
    boolean destroyed;

    private NativePathHttpServer(MemorySegment handle, int port, boolean tls, java.util.Set<String> secretHeaders) {
        this.handle = handle;
        this.port = port;
        this.tls = tls;
        this.secretHeaders = secretHeaders;
        int n = (int) ForeignBridge.serverLanes(handle);
        List<ServerLane> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            l.add(new ServerLane(this, i));
        }
        this.lanes = List.copyOf(l);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String host = "127.0.0.1";
        private int port = 0;
        private long maxBodyBytes = 16L * 1024 * 1024;
        private int lanes = 0;
        private java.nio.file.Path certChain;
        private java.nio.file.Path privateKey;
        private java.util.Set<String> secretHeaders = java.util.Set.of();
        // NativePathServerLimits field order (limits, then lane tuning); 0 keeps the engine default.
        private final long[] limits = new long[11];

        /**
         * Terminates TLS in the engine using PEM files. The engine reads them itself, so the private
         * key never passes through the JVM.
         */
        public Builder tls(java.nio.file.Path certChainPem, java.nio.file.Path privateKeyPem) {
            this.certChain = certChainPem;
            this.privateKey = privateKeyPem;
            return this;
        }

        /**
         * Request headers whose values must never become Java strings (case-insensitive): they are
         * left out of {@link ServerExchange#headers()} and read with
         * {@link ServerExchange#headerSecret(String)} instead.
         */
        public Builder secretHeaders(java.util.Collection<String> names) {
            java.util.Set<String> lower = new java.util.HashSet<>();
            for (String n : names) lower.add(n.toLowerCase(java.util.Locale.ROOT));
            this.secretHeaders = java.util.Set.copyOf(lower);
            return this;
        }

        /** IP address to bind (default 127.0.0.1; use 0.0.0.0 for all interfaces). */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** Port to bind; 0 (default) picks a free port, see {@link NativePathHttpServer#port()}. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Larger request bodies are answered with 413 and never reach a worker. */
        public Builder maxBodyBytes(long max) {
            this.maxBodyBytes = max;
            return this;
        }

        /**
         * Closes a connection whose request head is not complete this long after its first byte
         * (default 10 s).
         */
        public Builder headerReadTimeout(java.time.Duration timeout) {
            return limit(0, timeout.toMillis());
        }

        /**
         * Closes a keep-alive connection with no request for this long, or a response write that
         * makes no progress for this long (default 60 s).
         */
        public Builder idleTimeout(java.time.Duration timeout) {
            return limit(1, timeout.toMillis());
        }

        /** Request bodies not fully received within this time get 408 (default 30 s). */
        public Builder bodyReadTimeout(java.time.Duration timeout) {
            return limit(2, timeout.toMillis());
        }

        /** Closes connections whose TLS handshake takes longer (default 10 s). */
        public Builder tlsHandshakeTimeout(java.time.Duration timeout) {
            return limit(3, timeout.toMillis());
        }

        /** Larger request heads (request line and headers) get 431 (default 64 KB). */
        public Builder maxHeaderBytes(long max) {
            return limit(4, max);
        }

        /**
         * Open connections over all lanes; further ones are answered 503 and closed when accepted
         * (default 10,000).
         */
        public Builder maxConnections(long max) {
            return limit(5, max);
        }

        /**
         * Request body bytes buffered over all lanes and not yet answered; requests that would
         * exceed it get 503 (default 256 MB).
         */
        public Builder maxInflightBodyBytes(long max) {
            return limit(6, max);
        }

        private Builder limit(int field, long value) {
            if (value <= 0) {
                throw new IllegalArgumentException("limit must be positive: " + value);
            }
            limits[field] = value;
            return this;
        }

        /** Number of lanes (event loops), one worker thread each; 0 (default) means one per core. */
        public Builder lanes(int lanes) {
            this.lanes = lanes;
            return this;
        }

        /**
         * Requests a worker may take in a row without running its lane's event loop in between,
         * while more are already waiting (default 1: run it before every request). Replies to a
         * batch are written when the loop next runs: less overhead, a little more latency.
         */
        public Builder batch(int batch) {
            return limit(7, batch);
        }

        /**
         * Times a worker with nothing to take lets its lane's connections run again before it
         * sleeps (default 0). Cheaper under load, a little CPU when idle.
         */
        public Builder spin(int spin) {
            if (spin < 0) {
                throw new IllegalArgumentException("spin must be >= 0: " + spin);
            }
            limits[8] = spin;
            return this;
        }

        /**
         * Lets a worker with nothing to do take requests waiting on another lane whose worker is
         * busy (default off). The reply is still written by the owning lane.
         */
        public Builder workStealing(boolean on) {
            limits[9] = on ? 1 : 0;
            return this;
        }

        /**
         * Requests that may wait in one lane's queue; beyond it new requests are answered 503 with
         * {@code Retry-After} at once (default 0: no limit).
         */
        public Builder maxQueued(int max) {
            return limit(10, max);
        }

        public NativePathHttpServer start() {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            if (lanes < 0) {
                throw new IllegalArgumentException("lanes must be >= 0: " + lanes);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cHost = arena.allocateFrom(host, StandardCharsets.UTF_8);
                MemorySegment outPort = arena.allocate(ValueLayout.JAVA_SHORT);
                MemorySegment outError = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment cert = certChain == null ? MemorySegment.NULL
                        : arena.allocateFrom(certChain.toAbsolutePath().toString(), StandardCharsets.UTF_8);
                MemorySegment key = privateKey == null ? MemorySegment.NULL
                        : arena.allocateFrom(privateKey.toAbsolutePath().toString(), StandardCharsets.UTF_8);
                MemorySegment cLimits = arena.allocateFrom(ValueLayout.JAVA_LONG, limits);
                MemorySegment h = ForeignBridge.serverStart(cHost, (short) port, maxBodyBytes, lanes, cert, key, cLimits, outPort, outError);
                if (h.equals(MemorySegment.NULL)) {
                    MemorySegment err = outError.get(ValueLayout.ADDRESS, 0);
                    String message = err.equals(MemorySegment.NULL)
                            ? "unknown error"
                            : err.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
                    if (!err.equals(MemorySegment.NULL)) {
                        ForeignBridge.stringFree(err);
                    }
                    throw new IllegalStateException("NativePath server failed to start on " + host + ":" + port + ": " + message);
                }
                return new NativePathHttpServer(h, Short.toUnsignedInt(outPort.get(ValueLayout.JAVA_SHORT, 0)),
                        certChain != null, secretHeaders);
            }
        }
    }

    /** The bound port (the real one when started with port 0). */
    public int port() {
        return port;
    }

    /** True when the engine terminates TLS (https). */
    public boolean isTls() {
        return tls;
    }

    public boolean isRunning() {
        return running;
    }

    /** The lanes; drive each one from exactly one thread. */
    public List<ServerLane> lanes() {
        return lanes;
    }

    /**
     * Where request time goes inside the engine, summed over all requests since start (nanoseconds).
     *
     * @param requests  requests answered by a worker
     * @param readNanos head parsed until queued for the worker (body read, header packing)
     * @param queueNanos queued until the lane's worker took it
     * @param handlerNanos taken until answered: the Java side (adapter, framework, application)
     * @param respondNanos inside {@code respond}: header validation and the body copy
     * @param replyNanos answered until the engine picked the reply up to write it
     * @param shedRequests requests answered 503 because their lane's queue was full
     * @param shedConnections connections answered 503 because the connection limit was reached
     * @param stolen requests taken by another lane's worker (work stealing)
     */
    public record EngineStats(long requests, long readNanos, long queueNanos, long handlerNanos,
                              long respondNanos, long replyNanos, long shedRequests, long shedConnections,
                              long stolen) {}

    /** Phases of {@link #engineHistograms()}, in row order. */
    public static final String[] PHASES = {"read", "queue", "handler", "respond", "reply"};

    /** Buckets per phase in {@link #engineHistograms()}. */
    public static final int HIST_BUCKETS = 160;

    /**
     * How long each engine phase took, as counts since start: one row per {@link #PHASES}, in
     * log-scale buckets of nanoseconds, four per power of two (bucket {@code i} starts at
     * {@code i < 4 ? i : (4 + i % 4) << (i / 4 - 1)} ns). Shows the tail that {@link #engineStats()}'s
     * sums average away.
     */
    public long[][] engineHistograms() {
        lifecycle.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            long[][] rows = new long[PHASES.length][HIST_BUCKETS];
            if (destroyed) {
                return rows;
            }
            int n = PHASES.length * HIST_BUCKETS;
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG, n);
            ForeignBridge.serverPhaseHist(handle, out, n);
            long[] v = out.toArray(ValueLayout.JAVA_LONG);
            for (int p = 0; p < PHASES.length; p++) {
                System.arraycopy(v, p * HIST_BUCKETS, rows[p], 0, HIST_BUCKETS);
            }
            return rows;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Engine timings since start. Always collected (a few clock reads per request). */
    public EngineStats engineStats() {
        lifecycle.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            if (destroyed) {
                return new EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
            }
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG, 9);
            ForeignBridge.serverStats(handle, out, 9);
            long[] v = out.toArray(ValueLayout.JAVA_LONG);
            return new EngineStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Stops accepting and closes connections; threads in {@link ServerLane#poll} return {@code null}. */
    public void stop() {
        running = false;
        lifecycle.readLock().lock();
        try {
            if (!destroyed) {
                ForeignBridge.serverStop(handle);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void close() {
        stop(); // wakes pollers so the write lock below is granted quickly
        lifecycle.writeLock().lock();
        try {
            if (!destroyed) {
                destroyed = true;
                ForeignBridge.serverDestroy(handle);
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
