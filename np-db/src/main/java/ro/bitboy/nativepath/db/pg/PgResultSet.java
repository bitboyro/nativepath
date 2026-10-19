package ro.bitboy.nativepath.db.pg;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.db.jdbc.BaseResultSet;
import ro.bitboy.nativepath.db.jdbc.Unsupported;

import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Forward-only, read-only rows read in place from native memory.
 *
 * <p>Columns named in the connection's {@code secretColumns} refuse every getter that would copy
 * the value onto the heap ({@code getString}, {@code getBytes}, {@code getInt}, ...). Read them with
 * {@code getObject(i, NativeSecret.class)}, which copies native to native; {@code getObject(i)} on a
 * secret column also returns a {@link NativeSecret}.
 */
public final class PgResultSet extends BaseResultSet {

    private final PgStatement statement;
    private final PgResults owner;
    private final PgResults.Result result;
    private final long visibleRows;
    private final boolean[] secret;
    private Map<String, Integer> labels;
    private long row = -1;
    private boolean closed;
    private boolean wasNull;

    PgResultSet(PgStatement statement, PgResults owner, PgResults.Result result, int maxRows) {
        this.statement = statement;
        this.owner = owner;
        this.result = result;
        this.visibleRows = maxRows > 0 ? Math.min(maxRows, result.nrows()) : result.nrows();
        this.secret = new boolean[result.ncols()];
        var secretColumns = statement == null ? java.util.Set.<String>of() : statement.connection.secretColumns();
        for (int c = 0; c < secret.length; c++) {
            secret[c] = secretColumns.contains(result.names()[c].toLowerCase(Locale.ROOT));
        }
    }

    /** A result set with no columns and no rows (for example generated keys when none were requested). */
    static PgResultSet empty(PgStatement statement) {
        PgResults.Result r = new PgResults.Result(new String[0], new int[0], 0, MemorySegment.NULL, MemorySegment.NULL, 0, true, "");
        return new PgResultSet(statement, null, r, 0);
    }

    // ---------------------------------------------------------------- navigation

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (row + 1 < visibleRows) {
            row++;
            return true;
        }
        row = visibleRows;
        return false;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (statement != null) statement.resultSetClosed(this);
    }

    void closeQuietly() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed || (owner != null && owner.isClosed());
    }

    private void checkOpen() throws SQLException {
        if (isClosed()) throw new SQLException("result set is closed", "HY010");
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return row >= 0 && row < visibleRows ? (int) (row + 1) : 0;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return row < 0 && visibleRows > 0;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return row >= visibleRows && visibleRows > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return row == 0 && visibleRows > 0;
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return visibleRows > 0 && row == visibleRows - 1;
    }

    // ---------------------------------------------------------------- cell access

    private int col(int index) throws SQLException {
        checkOpen();
        if (index < 1 || index > result.ncols()) {
            throw new SQLException("column index " + index + " is out of range (1.." + result.ncols() + ")", "22023");
        }
        if (row < 0 || row >= visibleRows) throw new SQLException("the cursor is not on a row", "24000");
        return index - 1;
    }

    /** The cell's native bytes, or null for SQL NULL. Sets {@code wasNull}. */
    private MemorySegment cell(int c) {
        long len = result.length(row, c);
        wasNull = len < 0;
        return wasNull ? null : result.data().asSlice(result.offset(row, c), len);
    }

    private void refuseSecret(int c, String getter) throws SQLException {
        if (secret[c]) {
            throw new SQLException("column '" + result.names()[c] + "' is secret: " + getter
                    + " would copy it to the JVM heap; use getObject(i, NativeSecret.class)", "42501");
        }
    }

    /** Text of a non-secret cell, or null. */
    private String text(int index, String getter) throws SQLException {
        int c = col(index);
        refuseSecret(c, getter);
        MemorySegment s = cell(c);
        return s == null ? null : new String(s.toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private int oid(int index) {
        return result.typeOids()[index - 1];
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public String getString(int i) throws SQLException {
        return text(i, "getString");
    }

    @Override
    public String getNString(int i) throws SQLException {
        return text(i, "getNString");
    }

    @Override
    public boolean getBoolean(int i) throws SQLException {
        String s = text(i, "getBoolean");
        return s != null && PgTypes.parseBoolean(s);
    }

    @Override
    public byte getByte(int i) throws SQLException {
        String s = text(i, "getByte");
        return s == null ? 0 : (byte) PgTypes.parseLong(s, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    @Override
    public short getShort(int i) throws SQLException {
        String s = text(i, "getShort");
        return s == null ? 0 : (short) PgTypes.parseLong(s, Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    @Override
    public int getInt(int i) throws SQLException {
        String s = text(i, "getInt");
        return s == null ? 0 : (int) PgTypes.parseLong(s, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    @Override
    public long getLong(int i) throws SQLException {
        String s = text(i, "getLong");
        return s == null ? 0 : PgTypes.parseLong(s, Long.MIN_VALUE, Long.MAX_VALUE, "long");
    }

    @Override
    public float getFloat(int i) throws SQLException {
        String s = text(i, "getFloat");
        return s == null ? 0 : (float) PgTypes.parseDouble(s);
    }

    @Override
    public double getDouble(int i) throws SQLException {
        String s = text(i, "getDouble");
        return s == null ? 0 : PgTypes.parseDouble(s);
    }

    @Override
    public BigDecimal getBigDecimal(int i) throws SQLException {
        String s = text(i, "getBigDecimal");
        return s == null ? null : PgTypes.parseBigDecimal(s);
    }

    @Override
    @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(int i, int scale) throws SQLException {
        BigDecimal d = getBigDecimal(i);
        return d == null ? null : d.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int i) throws SQLException {
        int c = col(i);
        refuseSecret(c, "getBytes");
        MemorySegment s = cell(c);
        if (s == null) return null;
        byte[] raw = s.toArray(JAVA_BYTE);
        return oid(i) == PgTypes.BYTEA ? PgTypes.parseBytea(raw) : raw;
    }

    @Override
    public Date getDate(int i) throws SQLException {
        String s = text(i, "getDate");
        if (s == null) return null;
        if (oid(i) == PgTypes.TIMESTAMPTZ) return Date.valueOf(PgTypes.parseTimestampTz(s).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate());
        return Date.valueOf(PgTypes.parseDate(s));
    }

    @Override
    public Time getTime(int i) throws SQLException {
        String s = text(i, "getTime");
        if (s == null) return null;
        return switch (oid(i)) {
            case PgTypes.TIMESTAMP -> Time.valueOf(PgTypes.parseTimestamp(s).toLocalTime());
            case PgTypes.TIMESTAMPTZ -> Time.valueOf(PgTypes.parseTimestampTz(s).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime());
            default -> Time.valueOf(PgTypes.parseTime(s));
        };
    }

    @Override
    public Timestamp getTimestamp(int i) throws SQLException {
        String s = text(i, "getTimestamp");
        if (s == null) return null;
        return switch (oid(i)) {
            case PgTypes.TIMESTAMPTZ -> Timestamp.from(PgTypes.parseTimestampTz(s).toInstant());
            case PgTypes.DATE -> Timestamp.valueOf(PgTypes.parseDate(s).atStartOfDay());
            default -> Timestamp.valueOf(PgTypes.parseTimestamp(s));
        };
    }

    @Override
    public Object getObject(int i) throws SQLException {
        int c = col(i);
        if (secret[c]) return getObject(i, NativeSecret.class);
        String s = text(i, "getObject");
        if (s == null) return null;
        return switch (oid(i)) {
            case PgTypes.BOOL -> PgTypes.parseBoolean(s);
            case PgTypes.INT2, PgTypes.INT4 -> (int) PgTypes.parseLong(s, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
            case PgTypes.INT8, PgTypes.OID -> PgTypes.parseLong(s, Long.MIN_VALUE, Long.MAX_VALUE, "long");
            case PgTypes.FLOAT4 -> (float) PgTypes.parseDouble(s);
            case PgTypes.FLOAT8 -> PgTypes.parseDouble(s);
            case PgTypes.NUMERIC -> PgTypes.parseBigDecimal(s);
            case PgTypes.BYTEA -> PgTypes.parseBytea(s.getBytes(StandardCharsets.UTF_8));
            case PgTypes.DATE -> Date.valueOf(PgTypes.parseDate(s));
            case PgTypes.TIME, PgTypes.TIMETZ -> Time.valueOf(PgTypes.parseTime(s));
            case PgTypes.TIMESTAMP -> Timestamp.valueOf(PgTypes.parseTimestamp(s));
            case PgTypes.TIMESTAMPTZ -> Timestamp.from(PgTypes.parseTimestampTz(s).toInstant());
            case PgTypes.UUID -> UUID.fromString(s);
            default -> s;
        };
    }

    @Override
    public <T> T getObject(int i, Class<T> type) throws SQLException {
        if (type == null) throw new SQLException("type must not be null", "HY009");
        if (type == NativeSecret.class) return type.cast(secretCopy(i));
        Object v;
        if (type == String.class) v = getString(i);
        else if (type == Integer.class) v = getInt(i);
        else if (type == Long.class) v = getLong(i);
        else if (type == Short.class) v = getShort(i);
        else if (type == Byte.class) v = getByte(i);
        else if (type == Boolean.class) v = getBoolean(i);
        else if (type == Double.class) v = getDouble(i);
        else if (type == Float.class) v = getFloat(i);
        else if (type == BigDecimal.class) v = getBigDecimal(i);
        else if (type == BigInteger.class) v = map(getBigDecimal(i), BigDecimal::toBigIntegerExact);
        else if (type == byte[].class) v = getBytes(i);
        else if (type == Date.class) v = getDate(i);
        else if (type == Time.class) v = getTime(i);
        else if (type == Timestamp.class) v = getTimestamp(i);
        else if (type == UUID.class) v = map(getString(i), UUID::fromString);
        else if (type == LocalDate.class) v = map(getString(i), PgTypes::parseDate);
        else if (type == LocalTime.class) v = map(getString(i), PgTypes::parseTime);
        else if (type == LocalDateTime.class) v = map(getString(i), PgTypes::parseTimestamp);
        else if (type == OffsetDateTime.class) v = oid(i) == PgTypes.TIMESTAMP
                ? map(getString(i), s -> PgTypes.parseTimestamp(s).atOffset(ZoneOffset.UTC))
                : map(getString(i), PgTypes::parseTimestampTz);
        else if (type == Instant.class) v = map(getString(i), s -> PgTypes.parseTimestampTz(s).toInstant());
        else if (type == Object.class) v = getObject(i);
        else throw Unsupported.feature("getObject as " + type.getName());
        // Primitive-wrapper getters return 0/false for NULL; getObject must return null.
        return wasNull ? null : type.cast(v);
    }

    @FunctionalInterface
    private interface Conv<A, B> {
        B apply(A a) throws SQLException;
    }

    private static <A, B> B map(A a, Conv<A, B> f) throws SQLException {
        return a == null ? null : f.apply(a);
    }

    /** Copies the cell native to native into a secret that refuses heap copies. */
    private NativeSecret secretCopy(int i) throws SQLException {
        int c = col(i);
        MemorySegment s = cell(c);
        if (s == null) return null;
        NativeSecret out = NativeSecret.allocate(s.byteSize(), false);
        if (s.byteSize() > 0) MemorySegment.copy(s, 0, out.asMemorySegment(), 0, s.byteSize());
        return out;
    }

    // ---------------------------------------------------------------- by label

    @Override
    public int findColumn(String label) throws SQLException {
        checkOpen();
        if (labels == null) {
            labels = new HashMap<>();
            for (int c = result.ncols() - 1; c >= 0; c--) {
                labels.put(result.names()[c].toLowerCase(Locale.ROOT), c + 1); // first match wins
            }
        }
        Integer i = labels.get(label.toLowerCase(Locale.ROOT));
        if (i == null) throw new SQLException("no column named '" + label + "'", "42703");
        return i;
    }

    @Override public String getString(String l) throws SQLException { return getString(findColumn(l)); }
    @Override public String getNString(String l) throws SQLException { return getNString(findColumn(l)); }
    @Override public boolean getBoolean(String l) throws SQLException { return getBoolean(findColumn(l)); }
    @Override public byte getByte(String l) throws SQLException { return getByte(findColumn(l)); }
    @Override public short getShort(String l) throws SQLException { return getShort(findColumn(l)); }
    @Override public int getInt(String l) throws SQLException { return getInt(findColumn(l)); }
    @Override public long getLong(String l) throws SQLException { return getLong(findColumn(l)); }
    @Override public float getFloat(String l) throws SQLException { return getFloat(findColumn(l)); }
    @Override public double getDouble(String l) throws SQLException { return getDouble(findColumn(l)); }
    @Override public BigDecimal getBigDecimal(String l) throws SQLException { return getBigDecimal(findColumn(l)); }
    @Override public byte[] getBytes(String l) throws SQLException { return getBytes(findColumn(l)); }
    @Override public Date getDate(String l) throws SQLException { return getDate(findColumn(l)); }
    @Override public Time getTime(String l) throws SQLException { return getTime(findColumn(l)); }
    @Override public Timestamp getTimestamp(String l) throws SQLException { return getTimestamp(findColumn(l)); }
    @Override public Object getObject(String l) throws SQLException { return getObject(findColumn(l)); }
    @Override public <T> T getObject(String l, Class<T> type) throws SQLException { return getObject(findColumn(l), type); }

    @Override
    @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(String l, int scale) throws SQLException {
        return getBigDecimal(findColumn(l), scale);
    }

    // ---------------------------------------------------------------- properties

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new PgResultSetMetaData(result);
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public int getType() {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() {
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getFetchDirection() {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != ResultSet.FETCH_FORWARD) throw Unsupported.feature("Fetch direction other than FETCH_FORWARD");
    }

    @Override
    public int getFetchSize() {
        return 0;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) throw new SQLException("fetch size must not be negative", "HY024");
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
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("not a wrapper for " + iface.getName());
    }
}
