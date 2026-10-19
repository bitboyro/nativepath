package ro.bitboy.nativepath.spring.server.servlet;

import ro.bitboy.nativepath.NativePathHttpServer;
import ro.bitboy.nativepath.ServerExchange;
import ro.bitboy.nativepath.ServerLane;
import ro.bitboy.nativepath.spring.server.EngineSettings;
import ro.bitboy.nativepath.spring.server.InFlightLimit;
import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.UpgradeProtocol;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.SSLHostConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spike (Milestone 3): the NativePath engine as Tomcat's transport. Catalina (filters,
 * DispatcherServlet, Spring MVC) runs unchanged; this replaces Coyote's sockets, HTTP parsing and
 * TLS. Selected by {@link NativePathServletWebServerFactory}, which also passes TLS as PEM paths.
 *
 * <p><b>Threads.</b> One thread per lane drives the lane's I/O and hands each request to an
 * executor, so a blocking servlet never stalls the lane's other connections: by default a virtual
 * thread per request, or the executor Boot sets ({@code setExecutor}, e.g. with
 * {@code spring.threads.virtual.enabled}). With {@code dispatch=lane} requests run on the lane
 * thread itself, like the WebFlux adapter (fastest for handlers that never block).
 * Tomcat's own thread pool ({@code server.tomcat.threads.*}) is not used.
 *
 * <p><b>Async</b> requests ({@code AsyncContext}, {@code DeferredResult}, {@code Callable}) are
 * dispatched on the executor; async timeouts are checked once a second.
 */
public class NativePathProtocolHandler implements ProtocolHandler {

    private static final Log log = LogFactory.getLog(NativePathProtocolHandler.class);
    private static final long POLL_MILLIS = 200;
    private static final ThreadLocal<Boolean> ON_LANE = ThreadLocal.withInitial(() -> false);

    private Adapter adapter;
    private Executor executor;
    private ScheduledExecutorService utilityExecutor;
    private int port = 8080;
    private String address = "127.0.0.1";
    private int lanes = 0;
    private long maxBodyBytes = 16L * 1024 * 1024;
    private java.util.Collection<String> secretHeaders = List.of();
    private java.util.function.Consumer<NativePathHttpServer.Builder> limits = b -> { };
    private Path certificate;
    private Path privateKey;
    private String dispatch = "virtual";
    private int maxInFlight = 0;
    private EngineSettings engine = new EngineSettings();
    private volatile InFlightLimit limit = new InFlightLimit(0);
    private final List<Thread> workers = new ArrayList<>();
    private final ConcurrentLinkedQueue<NativePathProcessor> idle = new ConcurrentLinkedQueue<>();
    private final Set<NativePathProcessor> async = ConcurrentHashMap.newKeySet();
    private volatile NativePathHttpServer server;
    private volatile Executor requestExecutor;
    private ExecutorService ownExecutor;
    private ScheduledFuture<?> asyncTimeouts;
    private ScheduledExecutorService ownTimer;

    public NativePathProtocolHandler() {
    }

    // Properties, reached by Connector through IntrospectionUtils (port, localPort) or set by the factory.

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getPortOffset() {
        return 0;
    }

    /** Required: TomcatWebServer logs and reports {@code Connector.getLocalPort()}. */
    public int getLocalPort() {
        NativePathHttpServer s = server;
        return s == null ? -1 : s.port();
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setLanes(int lanes) {
        this.lanes = lanes;
    }

    /** Larger request bodies get 413 before reaching Java. */
    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    /** Request headers kept out of Java strings: absent from the servlet request; read them with {@code NativePathServletSecrets}. */
    public void setSecretHeaders(java.util.Collection<String> secretHeaders) {
        this.secretHeaders = secretHeaders;
    }

    /**
     * Sets connection limits (timeouts, connection cap, body budget) on the engine's builder before
     * it starts; by default the engine's own defaults apply.
     */
    public void setLimits(java.util.function.Consumer<NativePathHttpServer.Builder> limits) {
        this.limits = limits;
    }

    /** Whether the calling thread drives a lane (its writes must never block on that lane's I/O). */
    static boolean onLaneThread() {
        return ON_LANE.get();
    }

    /** {@code virtual} (default): requests run on an executor; {@code lane}: on the lane's thread. */
    public void setDispatch(String dispatch) {
        if (!dispatch.equals("virtual") && !dispatch.equals("lane")) {
            throw new IllegalArgumentException("dispatch must be 'virtual' or 'lane': " + dispatch);
        }
        this.dispatch = dispatch;
    }

    public String getDispatch() {
        return dispatch;
    }

    /**
     * Requests on the executor at once, async ones included until they complete (default 0: no
     * limit); ignored with dispatch {@code lane}. See {@link InFlightLimit}.
     */
    public void setMaxInFlight(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    /** Lane tuning and the queue limit; see {@link ro.bitboy.nativepath.spring.server.EngineSettings}. */
    public void setEngineSettings(EngineSettings engine) {
        this.engine = engine;
    }

    public void setTls(Path certificate, Path privateKey) {
        this.certificate = certificate;
        this.privateKey = privateKey;
    }

    /** The running engine (for metrics), or {@code null}. */
    public NativePathHttpServer server() {
        return server;
    }

    // ProtocolHandler

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    @Override
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    @Override
    public ScheduledExecutorService getUtilityExecutor() {
        return utilityExecutor;
    }

    @Override
    public void setUtilityExecutor(ScheduledExecutorService utilityExecutor) {
        this.utilityExecutor = utilityExecutor;
    }

    @Override
    public void init() {
    }

    @Override
    public synchronized void start() {
        if (server != null) {
            return;
        }
        NativePathHttpServer.Builder builder = NativePathHttpServer.builder().host(address).port(port).lanes(lanes)
                .maxBodyBytes(maxBodyBytes).secretHeaders(secretHeaders);
        engine.applyTo(builder);
        limits.accept(builder);
        if (certificate != null) {
            builder.tls(certificate, privateKey);
        }
        if (executor != null) {
            requestExecutor = executor;
        } else {
            ownExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nativepath-tomcat-", 0).factory());
            requestExecutor = ownExecutor;
        }
        ScheduledExecutorService timer = utilityExecutor;
        if (timer == null) {
            ownTimer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("nativepath-tomcat-timer").factory());
            timer = ownTimer;
        }
        asyncTimeouts = timer.scheduleWithFixedDelay(this::checkAsyncTimeouts, 1, 1, TimeUnit.SECONDS);
        NativePathHttpServer s = builder.start();
        server = s;
        boolean onLane = dispatch.equals("lane");
        limit = new InFlightLimit(onLane ? 0 : maxInFlight);
        for (ServerLane lane : s.lanes()) {
            workers.add(Thread.ofPlatform().name("nativepath-tomcat-lane-" + lane.index())
                    .start(() -> work(s, lane, onLane)));
        }
        log.info("NativePath transport started on port " + s.port() + (s.isTls() ? " (https)" : " (http)")
                + " with " + s.lanes().size() + " lanes, requests on "
                + (onLane ? "the lane threads" : executor != null ? "the configured executor" : "virtual threads"));
    }

    private void work(NativePathHttpServer s, ServerLane lane, boolean onLane) {
        ON_LANE.set(true);
        InFlightLimit limit = this.limit;
        while (s.isRunning()) {
            if (!limit.acquire(lane)) {
                return;
            }
            ServerExchange ex = lane.poll(POLL_MILLIS);
            if (ex == null) {
                limit.release();
                continue;
            }
            NativePathProcessor processor = acquire(s);
            if (onLane) {
                processor.process(ex);
            } else {
                try {
                    requestExecutor.execute(() -> processor.process(ex));
                } catch (RuntimeException rejected) {
                    log.error("Request rejected by the executor: " + ex.method() + " " + ex.target(), rejected);
                    ex.close(); // 500
                    release(processor);
                }
            }
        }
    }

    // Processors: one per request in flight, reused.

    private NativePathProcessor acquire(NativePathHttpServer s) {
        NativePathProcessor p = idle.poll();
        return p != null ? p : new NativePathProcessor(this, adapter, s.isTls(), s.port(), address);
    }

    /** The processor's request is finished (async ones included). */
    void release(NativePathProcessor p) {
        idle.offer(p);
        limit.release();
    }

    /** Where processors run requests and async dispatches. */
    Executor executor() {
        return requestExecutor;
    }

    /** The processor's request is waiting asynchronously (checked for async timeouts). */
    void waiting(NativePathProcessor p) {
        async.add(p);
    }

    void done(NativePathProcessor p) {
        async.remove(p);
    }

    private void checkAsyncTimeouts() {
        long now = System.currentTimeMillis();
        for (NativePathProcessor p : async) {
            try {
                p.timeoutAsync(now);
            } catch (RuntimeException e) {
                log.warn("Async timeout check failed", e);
            }
        }
    }

    @Override
    public void pause() {
        // The engine has no pause; Tomcat pauses before stop, and stop closes the engine.
    }

    @Override
    public void resume() {
    }

    @Override
    public synchronized void stop() {
        NativePathHttpServer s = server;
        if (s == null) {
            return;
        }
        s.close();
        for (Thread t : workers) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        workers.clear();
        server = null;
        if (asyncTimeouts != null) {
            asyncTimeouts.cancel(false);
        }
        if (ownTimer != null) {
            ownTimer.shutdownNow();
            ownTimer = null;
        }
        if (ownExecutor != null) {
            ownExecutor.shutdown();
            ownExecutor = null;
        }
        idle.clear();
        async.clear();
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public void closeServerSocketGraceful() {
    }

    @Override
    public long awaitConnectionsClose(long waitMillis) {
        return waitMillis;
    }

    @Override
    public boolean isSendfileSupported() {
        return false;
    }

    /** TLS comes from PEM paths ({@link #setTls}), never from a KeyStore; Tomcat's SSL config is ignored. */
    @Override
    public void addSslHostConfig(SSLHostConfig sslHostConfig) {
        log.warn("Ignoring SSLHostConfig " + sslHostConfig.getHostName() + ": NativePath takes TLS from PEM files");
    }

    @Override
    public void addSslHostConfig(SSLHostConfig sslHostConfig, boolean replace) {
        addSslHostConfig(sslHostConfig);
    }

    @Override
    public SSLHostConfig[] findSslHostConfigs() {
        return new SSLHostConfig[0];
    }

    @Override
    public void addUpgradeProtocol(UpgradeProtocol upgradeProtocol) {
        log.warn("Ignoring upgrade protocol " + upgradeProtocol.getClass().getName() + ": not supported");
    }

    @Override
    public UpgradeProtocol[] findUpgradeProtocols() {
        return new UpgradeProtocol[0];
    }
}
