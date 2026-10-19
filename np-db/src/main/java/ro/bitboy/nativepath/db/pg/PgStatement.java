package ro.bitboy.nativepath.db.pg;

import ro.bitboy.nativepath.db.DbObserver;
import ro.bitboy.nativepath.db.jdbc.BaseStatement;
import ro.bitboy.nativepath.db.jdbc.Unsupported;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain statement (simple query protocol, several statements allowed) and the shared result
 * handling that {@link PgPreparedStatement} builds on.
 */
public class PgStatement extends BaseStatement {

    final PgConnection connection;
    private boolean closed;
    private PgResults results;
    private int index;
    private PgResultSet current;
    private PgResultSet generatedKeys;
    private int maxRows;
    private int fetchSize;
    private int queryTimeoutSeconds;
    private boolean closeOnCompletion;
    private volatile boolean executing;
    private final List<String> batch = new ArrayList<>();

    PgStatement(PgConnection connection) {
        this.connection = connection;
    }

    // ---------------------------------------------------------------- shared result handling

    /** Replaces the current results with {@code next}; returns true if the first result has rows. */
    final boolean accept(PgResults next, boolean keysRequested) throws SQLException {
        clearResults();
        results = next;
        index = 0;
        if (keysRequested && !next.results().isEmpty() && next.results().get(0).hasRows()) {
            // The rows are the RETURNING clause added for generated keys, not a query result.
            generatedKeys = new PgResultSet(this, next, next.results().get(0), 0);
            return false;
        }
        return openCurrent();
    }

    private boolean openCurrent() throws SQLException {
        current = null;
        if (results == null || index >= results.results().size()) return false;
        PgResults.Result r = results.results().get(index);
        if (r.hasRows()) {
            current = new PgResultSet(this, results, r, maxRows);
            return true;
        }
        return false;
    }

    final void clearResults() {
        if (current != null) current.closeQuietly();
        if (generatedKeys != null) generatedKeys.closeQuietly();
        if (results != null) results.close();
        current = null;
        generatedKeys = null;
        results = null;
    }

    final PgResults runTimed(SqlCall call) throws SQLException {
        return runTimed(false, 0, call);
    }

    /**
     * Runs a statement, cancelling it on the server if it outlives the query timeout, and reports it
     * to the connection's {@link DbObserver}.
     */
    final PgResults runTimed(boolean batch, int secretParameters, SqlCall call) throws SQLException {
        checkOpen();
        QueryTimeout timeout = queryTimeoutSeconds > 0
                ? QueryTimeout.start(queryTimeoutSeconds * 1000L, connection::cancelQuietly) : null;
        DbObserver observer = connection.options().observer();
        long start = System.nanoTime();
        executing = true;
        try {
            PgResults r = call.run();
            if (observer != null) report(observer, batch, System.nanoTime() - start, r, secretParameters);
            return r;
        } catch (SQLException e) {
            boolean timedOut = timeout != null && timeout.fired() && CANCELLED.equals(e.getSQLState());
            if (observer != null) {
                DbObserver.Outcome outcome = timedOut ? DbObserver.Outcome.TIMEOUT
                        : CANCELLED.equals(e.getSQLState()) ? DbObserver.Outcome.CANCELLED : DbObserver.Outcome.ERROR;
                DbObserver.StatementEvent ev = new DbObserver.StatementEvent(batch ? DbObserver.Kind.BATCH : DbObserver.Kind.QUERY,
                        System.nanoTime() - start, outcome, e.getSQLState(), 0, 0, secretParameters);
                PgConnection.observe(observer, o -> o.statementFinished(ev));
            }
            if (timedOut) {
                throw new SQLTimeoutException("the statement exceeded the query timeout of " + queryTimeoutSeconds
                        + " s and was cancelled on the server", CANCELLED, e);
            }
            throw e;
        } finally {
            executing = false;
            if (timeout != null) timeout.close();
        }
    }

    private static void report(DbObserver observer, boolean batch, long nanos, PgResults r, int secretParameters) {
        long returned = 0;
        long affected = 0;
        boolean rows = false;
        for (PgResults.Result res : r.results()) {
            if (res.hasRows()) {
                rows = true;
                returned += res.nrows();
            } else {
                affected += res.rowsAffected();
            }
        }
        DbObserver.Kind kind = batch ? DbObserver.Kind.BATCH : rows ? DbObserver.Kind.QUERY : DbObserver.Kind.UPDATE;
        DbObserver.StatementEvent ev = new DbObserver.StatementEvent(kind, nanos, DbObserver.Outcome.SUCCESS, null,
                returned, affected, secretParameters);
        PgConnection.observe(observer, o -> o.statementFinished(ev));
    }

    /** SQLSTATE query_canceled. */
    static final String CANCELLED = "57014";

    /** Cancels this statement on the server if it is running (from another thread). */
    @Override
    public void cancel() throws SQLException {
        if (closed) throw new SQLException("statement is closed", "HY010");
        if (executing) connection.cancel();
    }

    @FunctionalInterface
    interface SqlCall {
        PgResults run() throws SQLException;
    }

    final void checkOpen() throws SQLException {
        if (closed) throw new SQLException("statement is closed", "HY010");
        if (connection.isClosed()) throw new SQLException("connection is closed", "08003");
    }

    /** Called by a result set when it closes, for {@link #closeOnCompletion()}. */
    void resultSetClosed(PgResultSet rs) {
        if (closeOnCompletion && rs == current) close();
    }

    // ---------------------------------------------------------------- Statement

    @Override
    public boolean execute(String sql) throws SQLException {
        return accept(runTimed(() -> connection.simple(sql)), false);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        boolean keys = autoGeneratedKeys == RETURN_GENERATED_KEYS;
        return accept(runTimed(() -> connection.simple(keys ? PgSql.withReturning(sql, null) : sql)), keys);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return accept(runTimed(() -> connection.simple(PgSql.withReturning(sql, columnNames))), true);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        if (!execute(sql)) throw new SQLException("the statement did not return a result set", "02000");
        return current;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        if (execute(sql)) throw new SQLException("a result set was returned where an update count was expected", "0100E");
        return updateCountOrZero();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        if (execute(sql, autoGeneratedKeys)) throw new SQLException("a result set was returned where an update count was expected", "0100E");
        return updateCountOrZero();
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        execute(sql, columnNames);
        return updateCountOrZero();
    }

    final int updateCountOrZero() throws SQLException {
        int n = getUpdateCount();
        return Math.max(n, 0);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return current;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        if (results == null || current != null || index >= results.results().size()) return -1;
        return (int) Math.min(Integer.MAX_VALUE, results.results().get(index).rowsAffected());
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        checkOpen();
        if (current != null) current.closeQuietly();
        index++;
        return openCurrent();
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return getMoreResults();
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        if (generatedKeys != null) return generatedKeys;
        return PgResultSet.empty(this);
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        checkOpen();
        batch.add(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        int[] counts = new int[batch.size()];
        try {
            for (int i = 0; i < counts.length; i++) {
                counts[i] = executeUpdate(batch.get(i));
            }
        } finally {
            batch.clear();
        }
        return counts;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clearResults();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        checkOpen();
        if (max < 0) throw new SQLException("maxRows must not be negative", "HY024");
        maxRows = max;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        if (rows < 0) throw new SQLException("fetch size must not be negative", "HY024");
        fetchSize = rows; // results are read in full; kept for getFetchSize
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    /**
     * A statement that runs longer is cancelled on the server (a CancelRequest, as psql's Ctrl-C) and
     * fails with {@link SQLTimeoutException}; the connection stays usable. Inside a transaction the
     * transaction is then aborted, as for any error.
     */
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        if (seconds < 0) throw new SQLException("query timeout must not be negative", "HY024");
        queryTimeoutSeconds = seconds;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return queryTimeoutSeconds;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();
        if (max != 0) throw Unsupported.feature("Statement.setMaxFieldSize");
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen(); // JDBC escape syntax is not translated; SQL is sent as written
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != ResultSet.FETCH_FORWARD) throw Unsupported.feature("Fetch direction other than FETCH_FORWARD");
    }

    @Override
    public int getFetchDirection() {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public int getResultSetType() {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetConcurrency() {
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        checkOpen();
    }

    @Override
    public boolean isPoolable() {
        return false;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() {
        return closeOnCompletion;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("not a wrapper for " + iface.getName());
    }
}
