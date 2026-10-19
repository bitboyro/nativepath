package ro.bitboy.nativepath.db;

/**
 * Receives driver events for metrics or logging. Pass one as the {@code observer} connection
 * property or with {@link NativePathDataSource#setObserver}; {@link ro.bitboy.nativepath.db.micrometer.NativePathDbMetrics}
 * turns them into Micrometer meters.
 *
 * <p>Events never carry SQL text or parameter values, so secrets cannot leak into telemetry and
 * tags stay low-cardinality. Methods are called on the thread that ran the operation and must be
 * fast; exceptions they throw are ignored.
 */
public interface DbObserver {

    default void connected(ConnectEvent event) {}

    default void statementFinished(StatementEvent event) {}

    /**
     * @param nanos        time to open the socket, negotiate TLS and authenticate
     * @param sqlState     null on success
     * @param encrypted    TLS in use
     * @param channelBound authentication used SCRAM-SHA-256-PLUS
     */
    record ConnectEvent(long nanos, boolean success, String sqlState, boolean encrypted, boolean channelBound) {}

    enum Kind { QUERY, UPDATE, BATCH }

    enum Outcome { SUCCESS, ERROR, TIMEOUT, CANCELLED }

    /**
     * One statement execution (each batch entry is one event).
     *
     * @param nanos            time from sending the statement to having its full result in native memory
     * @param sqlState         null on success
     * @param rowsReturned     rows in the result set, 0 for updates
     * @param rowsAffected     rows changed, 0 for queries
     * @param secretParameters parameters bound as {@code NativeSecret}
     */
    record StatementEvent(Kind kind, long nanos, Outcome outcome, String sqlState, long rowsReturned,
                          long rowsAffected, int secretParameters) {}
}
