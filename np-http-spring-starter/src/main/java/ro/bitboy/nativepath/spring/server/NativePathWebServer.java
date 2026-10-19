package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.NativePathHttpServer;
import ro.bitboy.nativepath.ServerExchange;
import ro.bitboy.nativepath.ServerLane;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.http.server.reactive.HttpHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A Spring Boot {@link WebServer} running a WebFlux {@link HttpHandler} on the native NativePath
 * engine. One worker thread per lane drives that lane's event loop. With dispatch {@code lane}
 * (default) it runs each of its requests through the handler on the same thread, as Netty does on
 * its event loops: handlers should not block for long, since the lane's other connections wait
 * meanwhile. With {@code virtual} each request runs on its own virtual thread, for applications
 * that block (JDBC, blocking clients), up to an {@link InFlightLimit}.
 */
public final class NativePathWebServer implements WebServer {

    private static final Log logger = LogFactory.getLog(NativePathWebServer.class);
    private static final long POLL_MILLIS = 200;

    private final HttpHandler handler;
    private final NativePathHttpServer.Builder builder;
    private final boolean virtual;
    private final int maxInFlight;
    private final List<Thread> workers = new ArrayList<>();
    private volatile NativePathHttpServer server;
    private ExecutorService executor;

    public NativePathWebServer(HttpHandler handler, NativePathHttpServer.Builder builder) {
        this(handler, builder, "lane", 0);
    }

    /**
     * @param dispatch    {@code lane} (requests run on the lane's thread) or {@code virtual} (one
     *                    virtual thread per request)
     * @param maxInFlight with {@code virtual}: requests running at once (0: no limit)
     */
    public NativePathWebServer(HttpHandler handler, NativePathHttpServer.Builder builder, String dispatch, int maxInFlight) {
        if (!dispatch.equals("lane") && !dispatch.equals("virtual")) {
            throw new IllegalArgumentException("dispatch must be 'lane' or 'virtual': " + dispatch);
        }
        this.handler = handler;
        this.builder = builder;
        this.virtual = dispatch.equals("virtual");
        this.maxInFlight = maxInFlight;
    }

    @Override
    public synchronized void start() throws WebServerException {
        if (server != null) {
            return;
        }
        try {
            server = builder.start();
        } catch (RuntimeException e) {
            throw new WebServerException("Unable to start NativePath server", e);
        }
        NativePathHttpServer s = server;
        InFlightLimit limit = new InFlightLimit(virtual ? maxInFlight : 0);
        if (virtual) {
            executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nativepath-request-", 0).factory());
        }
        for (ServerLane lane : s.lanes()) {
            workers.add(Thread.ofPlatform().name("nativepath-lane-" + lane.index())
                    .start(() -> work(s, lane, limit)));
        }
        logger.info("NativePath started on port " + s.port() + (s.isTls() ? " (https)" : " (http)")
                + " with " + s.lanes().size() + " lanes, requests on "
                + (virtual ? "virtual threads" + (maxInFlight > 0 ? " (at most " + maxInFlight + " at once)" : "")
                        : "the lane threads"));
    }

    private void work(NativePathHttpServer s, ServerLane lane, InFlightLimit limit) {
        while (s.isRunning()) {
            if (!virtual) {
                ServerExchange ex = lane.poll(POLL_MILLIS);
                if (ex != null) {
                    handle(ex);
                }
                continue;
            }
            if (!limit.acquire(lane)) {
                return;
            }
            ServerExchange ex = lane.poll(POLL_MILLIS);
            if (ex == null) {
                limit.release();
                continue;
            }
            try {
                executor.execute(() -> {
                    try {
                        handle(ex);
                    } finally {
                        limit.release();
                    }
                });
            } catch (RuntimeException rejected) { // stopping
                ex.close();
                limit.release();
            }
        }
    }

    private void handle(ServerExchange ex) {
        NativePathServerHttpResponse response = null;
        try {
            NativePathServerHttpRequest request;
            try {
                request = new NativePathServerHttpRequest(ex, server.isTls());
            } catch (IllegalArgumentException badRequest) {
                ex.respond(400, Map.of(), new byte[0]); // unparseable method or target
                return;
            }
            response = new NativePathServerHttpResponse(ex);
            handler.handle(request, response).block();
            response.send();
        } catch (Throwable t) {
            logger.error("Request failed: " + ex.method() + " " + ex.target(), t);
        } finally {
            if (response != null) {
                response.release();
            }
            ex.close(); // sends 500 if nothing was sent
        }
    }

    @Override
    public synchronized void stop() throws WebServerException {
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
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        server = null;
    }

    /** The running engine (for metrics such as {@link NativePathHttpServer#engineStats()}), or {@code null}. */
    public NativePathHttpServer server() {
        return server;
    }

    @Override
    public int getPort() {
        NativePathHttpServer s = server;
        return s == null ? -1 : s.port();
    }
}
