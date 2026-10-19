package ro.bitboy.nativepath.db.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ro.bitboy.nativepath.db.DbObserver;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer meters for the NativePath driver. Micrometer is an optional dependency: this class is
 * only loaded when used.
 *
 * <pre>{@code
 * dataSource.setObserver(new NativePathDbMetrics(meterRegistry, "orders"));
 * }</pre>
 *
 * <table>
 *   <caption>Meters (every meter has the tag {@code name})</caption>
 *   <tr><td>{@code nativepath.db.connect}</td><td>timer</td><td>{@code outcome}, {@code tls}, {@code channel_binding}</td></tr>
 *   <tr><td>{@code nativepath.db.statements}</td><td>timer</td><td>{@code kind} (query, update, batch),
 *       {@code outcome} (success, error, timeout, cancelled), {@code error} (SQLSTATE class, such as 23, or none)</td></tr>
 *   <tr><td>{@code nativepath.db.rows}</td><td>counter</td><td>{@code direction} (returned, affected)</td></tr>
 *   <tr><td>{@code nativepath.db.secret.parameters}</td><td>counter</td><td>parameters bound as NativeSecret</td></tr>
 * </table>
 */
public final class NativePathDbMetrics implements DbObserver {

    private final MeterRegistry registry;
    private final String name;
    private final Counter rowsReturned;
    private final Counter rowsAffected;
    private final Counter secretParameters;

    public NativePathDbMetrics(MeterRegistry registry, String name) {
        this.registry = registry;
        this.name = name;
        this.rowsReturned = Counter.builder("nativepath.db.rows").description("Rows returned or changed")
                .tags("name", name, "direction", "returned").register(registry);
        this.rowsAffected = Counter.builder("nativepath.db.rows").description("Rows returned or changed")
                .tags("name", name, "direction", "affected").register(registry);
        this.secretParameters = Counter.builder("nativepath.db.secret.parameters")
                .description("Statement parameters bound as NativeSecret (kept off the JVM heap)")
                .tags("name", name).register(registry);
    }

    @Override
    public void connected(ConnectEvent e) {
        Timer.builder("nativepath.db.connect")
                .description("Opening a connection: socket, TLS and authentication")
                .tags("name", name,
                        "outcome", e.success() ? "success" : "error",
                        "tls", Boolean.toString(e.encrypted()),
                        "channel_binding", Boolean.toString(e.channelBound()))
                .register(registry)
                .record(e.nanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void statementFinished(StatementEvent e) {
        Timer.builder("nativepath.db.statements")
                .description("Statement execution until the full result is in native memory")
                .tags("name", name,
                        "kind", e.kind().name().toLowerCase(Locale.ROOT),
                        "outcome", e.outcome().name().toLowerCase(Locale.ROOT),
                        "error", e.sqlState() == null || e.sqlState().length() < 2 ? "none" : e.sqlState().substring(0, 2))
                .register(registry)
                .record(e.nanos(), TimeUnit.NANOSECONDS);
        if (e.rowsReturned() > 0) rowsReturned.increment(e.rowsReturned());
        if (e.rowsAffected() > 0) rowsAffected.increment(e.rowsAffected());
        if (e.secretParameters() > 0) secretParameters.increment(e.secretParameters());
    }
}
