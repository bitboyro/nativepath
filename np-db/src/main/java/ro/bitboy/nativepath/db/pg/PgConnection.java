package ro.bitboy.nativepath.db.pg;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.db.DbObserver;
import ro.bitboy.nativepath.db.jdbc.BaseConnection;
import ro.bitboy.nativepath.db.jdbc.Unsupported;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A JDBC connection backed by the native PostgreSQL engine. Native calls are serialized on this
 * object; JDBC connections are not meant to be shared between threads anyway.
 */
public final class PgConnection extends BaseConnection {

    /** A bind value: text from the heap, a native secret, or SQL NULL (both null). */
    record Param(byte[] text, NativeSecret secret, int typeOid) {
        static final Param NULL = new Param(null, null, PgTypes.UNSPECIFIED);
    }

    private final PgOptions options;
    private MemorySegment handle;
    /** Independent of {@link #handle}: cancelling must not wait for the connection lock a running statement holds. */
    private MemorySegment cancelToken;
    private final Object cancelLock = new Object();
    private boolean autoCommit = true;
    private boolean readOnly;
    private int isolation = TRANSACTION_READ_COMMITTED;
    private int networkTimeoutMs;

    private PgConnection(PgOptions options, MemorySegment handle) {
        this.options = options;
        this.handle = handle;
        this.cancelToken = PgNative.cancelToken(handle);
        this.networkTimeoutMs = options.socketTimeoutMs();
        if (networkTimeoutMs > 0) PgNative.setTimeout(handle, networkTimeoutMs);
    }

    /**
     * Opens a connection. A password given as a {@link SecretSource} or a {@code passwordFile} is
     * loaded for this connection only and wiped as soon as authentication ends. A {@link NativeSecret}
     * is read in place and stays owned by the caller.
     */
    public static PgConnection open(PgOptions o) throws SQLException {
        long start = System.nanoTime();
        try {
            PgConnection c = connect(o);
            boolean tls = c.isEncrypted();
            boolean bound = c.isChannelBound();
            observe(o.observer(), obs -> obs.connected(new DbObserver.ConnectEvent(System.nanoTime() - start, true, null, tls, bound)));
            return c;
        } catch (SQLException e) {
            observe(o.observer(), obs -> obs.connected(new DbObserver.ConnectEvent(System.nanoTime() - start, false, e.getSQLState(), false, false)));
            throw e;
        }
    }

    /** Calls the observer, if any; its exceptions never reach the application. */
    static void observe(DbObserver observer, java.util.function.Consumer<DbObserver> event) {
        if (observer == null) return;
        try {
            event.accept(observer);
        } catch (RuntimeException ignored) {
            // A broken metrics hook must not break database access.
        }
    }

    private static PgConnection connect(PgOptions o) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            NativeSecret loaded = null;
            MemorySegment pwCopy = null;
            try {
                MemorySegment pw = MemorySegment.NULL;
                long pwLen = -1;
                Object p = o.password();
                if (p == null && o.passwordFile() != null) {
                    p = (SecretSource) () -> NativeSecret.fromFile(Path.of(o.passwordFile()));
                }
                if (p instanceof SecretSource src) {
                    loaded = src.load();
                    p = loaded;
                }
                if (p instanceof NativeSecret s) {
                    pw = s.length() == 0 ? arena.allocate(1) : s.asMemorySegment();
                    pwLen = s.length();
                } else if (p instanceof char[] chars) {
                    pwCopy = utf8(arena, chars);
                    pw = pwCopy;
                    pwLen = pwCopy.byteSize();
                } else if (p instanceof String str) {
                    // Already a heap String; the native copy is wiped below.
                    pwCopy = arena.allocateFrom(JAVA_BYTE, str.getBytes(StandardCharsets.UTF_8));
                    pw = pwCopy;
                    pwLen = pwCopy.byteSize();
                } else if (p != null) {
                    throw new SQLException("unsupported password type " + p.getClass().getName()
                            + " (use NativeSecret, SecretSource, String or char[])", "28000");
                }
                MemorySegment err = arena.allocate(ADDRESS);
                MemorySegment rootCert = o.sslRootCert() == null ? MemorySegment.NULL : arena.allocateFrom(o.sslRootCert());
                MemorySegment h = PgNative.connect(arena.allocateFrom(o.host()), o.port(), arena.allocateFrom(o.user()),
                        arena.allocateFrom(o.database()), arena.allocateFrom(o.applicationName()), pw, pwLen,
                        o.connectTimeoutMs(), o.sslMode().ordinal(), rootCert, o.channelBinding().ordinal(), err);
                if (h.equals(MemorySegment.NULL)) {
                    throw PgErrors.take(err.get(ADDRESS, 0), o.errorDetail());
                }
                return new PgConnection(o, h);
            } catch (IOException e) {
                throw new SQLException("could not load the password: " + e.getMessage(), "28000", e);
            } finally {
                if (pwCopy != null) pwCopy.fill((byte) 0);
                if (loaded != null) loaded.close();
            }
        }
    }

    private static MemorySegment utf8(Arena arena, char[] chars) {
        java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        MemorySegment seg = arena.allocate(Math.max(1, bb.remaining()));
        int n = bb.remaining();
        MemorySegment.copy(MemorySegment.ofBuffer(bb), 0, seg, 0, n);
        // Wipe the heap buffer the encoder produced.
        java.util.Arrays.fill(bb.array(), (byte) 0);
        return seg.asSlice(0, n);
    }

    PgOptions options() {
        return options;
    }

    Set<String> secretColumns() {
        return options.secretColumns();
    }

    // ---------------------------------------------------------------- execution

    synchronized PgResults execute(String sql, List<Param> params) throws SQLException {
        checkOpen();
        beginIfNeeded();
        return run(sql, params);
    }

    synchronized PgResults simple(String sql) throws SQLException {
        checkOpen();
        beginIfNeeded();
        return runSimple(sql);
    }

    private PgResults run(String sql, List<Param> params) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            MemorySegment sqlSeg = arena.allocateFrom(JAVA_BYTE, sqlBytes);
            MemorySegment ps = params.isEmpty() ? MemorySegment.NULL : arena.allocate(PgNative.PARAM, params.size());
            for (int i = 0; i < params.size(); i++) {
                Param p = params.get(i);
                if (p == null) throw new SQLException("no value specified for parameter " + (i + 1), "07001");
                long base = i * PgNative.PARAM.byteSize();
                MemorySegment ptr = MemorySegment.NULL;
                long len = -1;
                if (p.secret() != null) {
                    if (p.secret().isClosed()) throw new SQLException("parameter " + (i + 1) + " is a closed NativeSecret", "07001");
                    ptr = p.secret().length() == 0 ? MemorySegment.NULL : p.secret().asMemorySegment();
                    len = p.secret().length();
                } else if (p.text() != null) {
                    ptr = p.text().length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_BYTE, p.text());
                    len = p.text().length;
                }
                ps.set(ADDRESS, base, ptr);
                ps.set(JAVA_LONG, base + 8, len);
                ps.set(JAVA_INT, base + 16, p.typeOid());
            }
            MemorySegment err = arena.allocate(ADDRESS);
            MemorySegment r = PgNative.execute(handle, sqlSeg, sqlBytes.length, ps, params.size(), err);
            if (r.equals(MemorySegment.NULL)) throw PgErrors.take(err.get(ADDRESS, 0), options.errorDetail());
            return new PgResults(r);
        }
    }

    private PgResults runSimple(String sql) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            MemorySegment err = arena.allocate(ADDRESS);
            MemorySegment r = PgNative.simple(handle, arena.allocateFrom(JAVA_BYTE, sqlBytes), sqlBytes.length, err);
            if (r.equals(MemorySegment.NULL)) throw PgErrors.take(err.get(ADDRESS, 0), options.errorDetail());
            return new PgResults(r);
        }
    }

    private void command(String sql) throws SQLException {
        try (PgResults ignored = runSimple(sql)) {
            // only the outcome matters
        }
    }

    private void beginIfNeeded() throws SQLException {
        if (!autoCommit && txStatus() == 'I') command("BEGIN");
    }

    private byte txStatus() {
        return PgNative.txStatus(handle);
    }

    private void checkOpen() throws SQLException {
        if (handle == null) throw new SQLNonTransientConnectionException("connection is closed", "08003");
    }

    // ---------------------------------------------------------------- statements

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new PgStatement(this);
    }

    @Override
    public Statement createStatement(int type, int concurrency) throws SQLException {
        checkResultSetType(type, concurrency);
        return createStatement();
    }

    @Override
    public Statement createStatement(int type, int concurrency, int holdability) throws SQLException {
        checkResultSetType(type, concurrency);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        return new PgPreparedStatement(this, sql, false, null);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkOpen();
        return new PgPreparedStatement(this, sql, autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS, null);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkOpen();
        return new PgPreparedStatement(this, sql, true, columnNames);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int type, int concurrency) throws SQLException {
        checkResultSetType(type, concurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int type, int concurrency, int holdability) throws SQLException {
        checkResultSetType(type, concurrency);
        return prepareStatement(sql);
    }

    private static void checkResultSetType(int type, int concurrency) throws SQLException {
        if (type != ResultSet.TYPE_FORWARD_ONLY || concurrency != ResultSet.CONCUR_READ_ONLY) {
            throw Unsupported.feature("Scrollable or updatable result sets");
        }
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return PgSql.rewritePlaceholders(sql).sql();
    }

    // ---------------------------------------------------------------- transactions

    @Override
    public synchronized void setAutoCommit(boolean enabled) throws SQLException {
        checkOpen();
        if (enabled == autoCommit) return;
        if (enabled && txStatus() != 'I') commit();
        autoCommit = enabled;
    }

    @Override
    public synchronized boolean getAutoCommit() throws SQLException {
        checkOpen();
        return autoCommit;
    }

    @Override
    public synchronized void commit() throws SQLException {
        checkOpen();
        if (autoCommit) throw new SQLException("cannot commit when autoCommit is enabled", "25000");
        if (txStatus() == 'I') return;
        try (PgResults r = runSimple("COMMIT")) {
            if (!r.results().isEmpty() && "ROLLBACK".equals(r.results().get(0).tag())) {
                throw new SQLException("the transaction had failed and was rolled back, not committed", "25P02");
            }
        }
    }

    @Override
    public synchronized void rollback() throws SQLException {
        checkOpen();
        if (autoCommit) throw new SQLException("cannot roll back when autoCommit is enabled", "25000");
        if (txStatus() != 'I') command("ROLLBACK");
    }

    @Override
    public synchronized void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
        String name = switch (level) {
            case TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED";
            case TRANSACTION_READ_COMMITTED -> "READ COMMITTED";
            case TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ";
            case TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> throw new SQLException("unsupported isolation level " + level, "HY024");
        };
        if (txStatus() != 'I') throw new SQLException("cannot change isolation inside a transaction", "25001");
        command("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + name);
        isolation = level;
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return isolation;
    }

    @Override
    public synchronized void setReadOnly(boolean ro) throws SQLException {
        checkOpen();
        if (ro == readOnly) return;
        if (txStatus() != 'I') throw new SQLException("cannot change read-only inside a transaction", "25001");
        command("SET SESSION CHARACTERISTICS AS TRANSACTION " + (ro ? "READ ONLY" : "READ WRITE"));
        readOnly = ro;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return readOnly;
    }

    // ---------------------------------------------------------------- lifecycle and health

    @Override
    public synchronized void close() {
        if (handle == null) return;
        MemorySegment h = handle;
        handle = null;
        synchronized (cancelLock) {
            PgNative.cancelTokenFree(cancelToken);
            cancelToken = null;
        }
        PgNative.close(h);
    }

    /**
     * Asks the server to cancel the statement running on this connection. Safe from any thread and
     * does not wait for the running statement; returns once the server has taken the request. A
     * cancel that reaches an idle connection is ignored by the server.
     */
    void cancel() throws SQLException {
        synchronized (cancelLock) {
            if (cancelToken == null) return;
            MemorySegment err = PgNative.cancel(cancelToken);
            if (!err.equals(MemorySegment.NULL)) throw PgErrors.take(err, false);
        }
    }

    void cancelQuietly() {
        try {
            cancel();
        } catch (SQLException | RuntimeException e) {
            // Best effort: if the cancel cannot be sent, the statement simply runs to completion.
        }
    }

    /** True when the connection is encrypted with TLS. */
    public synchronized boolean isEncrypted() {
        return handle != null && (PgNative.security(handle) & 1) != 0;
    }

    /** True when authentication used SCRAM-SHA-256-PLUS, bound to this TLS session. */
    public synchronized boolean isChannelBound() {
        return handle != null && (PgNative.security(handle) & 2) != 0;
    }

    @Override
    public synchronized boolean isClosed() {
        return handle == null;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) throw new SQLException("executor must not be null", "HY009");
        // Cancel first, so a running statement returns and close() can take the connection lock.
        executor.execute(() -> {
            cancelQuietly();
            close();
        });
    }

    @Override
    public synchronized boolean isValid(int timeoutSeconds) throws SQLException {
        if (timeoutSeconds < 0) throw new SQLException("timeout must not be negative", "HY024");
        if (handle == null || txStatus() == 'X') return false;
        try {
            PgNative.setTimeout(handle, timeoutSeconds * 1000);
            command("");
            return true;
        } catch (SQLException e) {
            return false;
        } finally {
            if (handle != null) PgNative.setTimeout(handle, networkTimeoutMs);
        }
    }

    @Override
    public synchronized void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkOpen();
        if (milliseconds < 0) throw new SQLException("timeout must not be negative", "HY024");
        networkTimeoutMs = milliseconds;
        PgNative.setTimeout(handle, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return networkTimeoutMs;
    }

    // ---------------------------------------------------------------- session properties

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new PgDatabaseMetaData(this);
    }

    synchronized String serverParameter(String name) {
        if (handle == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            return PgNative.parameter(handle, arena.allocateFrom(name));
        }
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return options.database();
    }

    @Override
    public void setCatalog(String catalog) {
        // PostgreSQL cannot switch databases on a connection; ignored, as in pgjdbc.
    }

    @Override
    public synchronized String getSchema() throws SQLException {
        checkOpen();
        try (PgResults r = runSimple("SELECT current_schema()")) {
            PgResults.Result res = r.results().get(0);
            if (res.nrows() == 0 || res.length(0, 0) < 0) return null;
            return new String(res.data().asSlice(res.offset(0, 0), res.length(0, 0)).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }

    @Override
    public synchronized void setSchema(String schema) throws SQLException {
        checkOpen();
        command("SET search_path TO " + PgSql.quoteIdentifier(schema));
    }

    @Override
    public int getHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT && holdability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            throw new SQLException("invalid holdability " + holdability, "HY024");
        }
        // Results are read in full, so both behave the same.
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
    public Map<String, Class<?>> getTypeMap() {
        return new HashMap<>();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) throw Unsupported.feature("Custom type maps");
    }

    @Override
    public Properties getClientInfo() {
        return new Properties();
    }

    @Override
    public String getClientInfo(String name) {
        return null;
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        // Not supported; ignored rather than failing pools that set it.
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        // Not supported; ignored rather than failing pools that set it.
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
