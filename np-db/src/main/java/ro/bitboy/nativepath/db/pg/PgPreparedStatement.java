package ro.bitboy.nativepath.db.pg;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.db.jdbc.Unsupported;
import ro.bitboy.nativepath.db.jdbc.UnsupportedPreparedStatement;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A prepared statement run with the extended protocol (unnamed statement, text format).
 *
 * <p>Secrets: pass a {@link NativeSecret} with {@code setObject(i, secret)}. Its bytes go from
 * native memory into the protocol buffer, which is wiped once the statement completes; no Java
 * copy is made. The caller keeps ownership and closes it.
 */
public final class PgPreparedStatement extends PgStatement implements UnsupportedPreparedStatement {

    private final String sql;
    private final int parameterCount;
    private final boolean keysRequested;
    private final PgConnection.Param[] params;
    private final List<PgConnection.Param[]> batch = new ArrayList<>();

    PgPreparedStatement(PgConnection connection, String sql, boolean keysRequested, String[] keyColumns) throws SQLException {
        super(connection);
        PgSql.Rewritten rw = PgSql.rewritePlaceholders(sql);
        this.sql = keysRequested ? PgSql.withReturning(rw.sql(), keyColumns) : rw.sql();
        this.parameterCount = rw.parameterCount();
        this.keysRequested = keysRequested;
        this.params = new PgConnection.Param[parameterCount];
    }

    // ---------------------------------------------------------------- execution

    @Override
    public boolean execute() throws SQLException {
        List<PgConnection.Param> values = Arrays.asList(params.clone());
        return accept(runTimed(false, secrets(values), () -> connection.execute(sql, values)), keysRequested);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (!execute()) throw new SQLException("the statement did not return a result set", "02000");
        return getResultSet();
    }

    @Override
    public int executeUpdate() throws SQLException {
        if (execute()) throw new SQLException("a result set was returned where an update count was expected", "0100E");
        return updateCountOrZero();
    }

    @Override
    public void addBatch() throws SQLException {
        checkOpen();
        batch.add(params.clone());
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
                List<PgConnection.Param> values = Arrays.asList(batch.get(i));
                if (accept(runTimed(true, secrets(values), () -> connection.execute(sql, values)), keysRequested)) {
                    throw new java.sql.BatchUpdateException("a batch statement returned a result set", "0100E",
                            Arrays.copyOf(counts, i));
                }
                counts[i] = updateCountOrZero();
            }
        } finally {
            batch.clear();
        }
        return counts;
    }

    private static int secrets(List<PgConnection.Param> values) {
        int n = 0;
        for (PgConnection.Param p : values) {
            if (p != null && p.secret() != null) n++;
        }
        return n;
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return null; // known only after execution
    }

    // Statement methods that take SQL are not allowed on a PreparedStatement (JDBC 4.3, 13.2.4).

    @Override
    public boolean execute(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw notOnPrepared();
    }

    private static SQLException notOnPrepared() {
        return new SQLException("this method cannot be called on a PreparedStatement", "HY000");
    }

    // ---------------------------------------------------------------- parameters

    private void set(int index, PgConnection.Param p) throws SQLException {
        checkOpen();
        if (index < 1 || index > parameterCount) {
            throw new SQLException("parameter index " + index + " is out of range (1.." + parameterCount + ")", "22023");
        }
        params[index - 1] = p;
    }

    private void setText(int index, String text, int oid) throws SQLException {
        set(index, new PgConnection.Param(text.getBytes(StandardCharsets.UTF_8), null, oid));
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        Arrays.fill(params, null);
    }

    @Override
    public void setNull(int index, int sqlType) throws SQLException {
        set(index, PgConnection.Param.NULL);
    }

    @Override
    public void setNull(int index, int sqlType, String typeName) throws SQLException {
        set(index, PgConnection.Param.NULL);
    }

    @Override
    public void setBoolean(int index, boolean x) throws SQLException {
        setText(index, x ? "true" : "false", PgTypes.BOOL);
    }

    @Override
    public void setByte(int index, byte x) throws SQLException {
        setText(index, Byte.toString(x), PgTypes.INT2);
    }

    @Override
    public void setShort(int index, short x) throws SQLException {
        setText(index, Short.toString(x), PgTypes.INT2);
    }

    @Override
    public void setInt(int index, int x) throws SQLException {
        setText(index, Integer.toString(x), PgTypes.INT4);
    }

    @Override
    public void setLong(int index, long x) throws SQLException {
        setText(index, Long.toString(x), PgTypes.INT8);
    }

    @Override
    public void setFloat(int index, float x) throws SQLException {
        setText(index, Float.toString(x), PgTypes.FLOAT4);
    }

    @Override
    public void setDouble(int index, double x) throws SQLException {
        setText(index, Double.toString(x), PgTypes.FLOAT8);
    }

    @Override
    public void setBigDecimal(int index, BigDecimal x) throws SQLException {
        if (x == null) set(index, PgConnection.Param.NULL);
        else setText(index, x.toPlainString(), PgTypes.NUMERIC);
    }

    @Override
    public void setString(int index, String x) throws SQLException {
        if (x == null) set(index, PgConnection.Param.NULL);
        else setText(index, x, PgTypes.UNSPECIFIED);
    }

    @Override
    public void setNString(int index, String x) throws SQLException {
        setString(index, x);
    }

    @Override
    public void setBytes(int index, byte[] x) throws SQLException {
        if (x == null) set(index, PgConnection.Param.NULL);
        else setText(index, PgTypes.byteaHex(x), PgTypes.BYTEA);
    }

    @Override
    public void setDate(int index, Date x) throws SQLException {
        if (x == null) set(index, PgConnection.Param.NULL);
        else setText(index, x.toLocalDate().toString(), PgTypes.DATE);
    }

    @Override
    public void setTime(int index, Time x) throws SQLException {
        if (x == null) set(index, PgConnection.Param.NULL);
        else setText(index, x.toLocalTime().toString(), PgTypes.TIME);
    }

    /**
     * Sent without a type, as pgjdbc does, so a {@code timestamptz} column reads it in the session
     * time zone and a {@code timestamp} column stores it as written.
     */
    @Override
    public void setTimestamp(int index, Timestamp x) throws SQLException {
        if (x == null) set(index, PgConnection.Param.NULL);
        else setText(index, PgTypes.timestampText(x.toLocalDateTime()), PgTypes.UNSPECIFIED);
    }

    @Override
    public void setObject(int index, Object x, int targetSqlType) throws SQLException {
        setObject(index, x);
    }

    @Override
    public void setObject(int index, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(index, x);
    }

    @Override
    public void setObject(int index, Object x) throws SQLException {
        switch (x) {
            case null -> set(index, PgConnection.Param.NULL);
            case NativeSecret s -> set(index, new PgConnection.Param(null, s, PgTypes.UNSPECIFIED));
            case String s -> setString(index, s);
            case Integer i -> setInt(index, i);
            case Long l -> setLong(index, l);
            case Short s -> setShort(index, s);
            case Byte b -> setByte(index, b);
            case Boolean b -> setBoolean(index, b);
            case Double d -> setDouble(index, d);
            case Float f -> setFloat(index, f);
            case BigDecimal d -> setBigDecimal(index, d);
            case BigInteger b -> setText(index, b.toString(), PgTypes.NUMERIC);
            case byte[] b -> setBytes(index, b);
            case Timestamp t -> setTimestamp(index, t);
            case Date d -> setDate(index, d);
            case Time t -> setTime(index, t);
            case LocalDate d -> setText(index, d.toString(), PgTypes.DATE);
            case LocalTime t -> setText(index, t.toString(), PgTypes.TIME);
            case LocalDateTime t -> setText(index, PgTypes.timestampText(t), PgTypes.TIMESTAMP);
            case OffsetDateTime t -> setText(index, t.toString(), PgTypes.TIMESTAMPTZ);
            case ZonedDateTime t -> setText(index, t.toOffsetDateTime().toString(), PgTypes.TIMESTAMPTZ);
            case Instant t -> setText(index, t.atOffset(ZoneOffset.UTC).toString(), PgTypes.TIMESTAMPTZ);
            case OffsetTime t -> setText(index, t.toString(), PgTypes.TIMETZ);
            case UUID u -> setText(index, u.toString(), PgTypes.UUID);
            case Character c -> setString(index, c.toString());
            case Enum<?> e -> setString(index, e.name());
            default -> throw new SQLException("cannot bind a value of type " + x.getClass().getName(), "22023");
        }
    }

    @Override
    public void setTimestamp(int index, Timestamp x, java.util.Calendar cal) throws SQLException {
        if (cal == null) {
            setTimestamp(index, x);
            return;
        }
        throw Unsupported.feature("PreparedStatement.setTimestamp with a Calendar");
    }
}
