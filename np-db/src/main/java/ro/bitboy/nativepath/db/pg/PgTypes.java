package ro.bitboy.nativepath.db.pg;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;

/** PostgreSQL type OIDs and conversions between their text format and Java values. */
final class PgTypes {

    static final int BOOL = 16;
    static final int BYTEA = 17;
    static final int NAME = 19;
    static final int INT8 = 20;
    static final int INT2 = 21;
    static final int INT4 = 23;
    static final int TEXT = 25;
    static final int OID = 26;
    static final int JSON = 114;
    static final int FLOAT4 = 700;
    static final int FLOAT8 = 701;
    static final int BPCHAR = 1042;
    static final int VARCHAR = 1043;
    static final int DATE = 1082;
    static final int TIME = 1083;
    static final int TIMESTAMP = 1114;
    static final int TIMESTAMPTZ = 1184;
    static final int TIMETZ = 1266;
    static final int NUMERIC = 1700;
    static final int UUID = 2950;
    static final int JSONB = 3802;
    /** Let the server infer the parameter type. */
    static final int UNSPECIFIED = 0;

    private static final DateTimeFormatter TIMESTAMP_TEXT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();
    private static final DateTimeFormatter TIMESTAMPTZ_TEXT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME)
            .optionalStart().appendPattern("XXXXX").optionalEnd()
            .optionalStart().appendPattern("X").optionalEnd()
            .toFormatter();
    private static final DateTimeFormatter TIMETZ_TEXT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .optionalStart().appendPattern("XXXXX").optionalEnd()
            .optionalStart().appendPattern("X").optionalEnd()
            .toFormatter();

    static int sqlType(int oid) {
        return switch (oid) {
            case BOOL -> Types.BIT;
            case BYTEA -> Types.BINARY;
            case INT8, OID -> Types.BIGINT;
            case INT2 -> Types.SMALLINT;
            case INT4 -> Types.INTEGER;
            case TEXT, NAME, VARCHAR, JSON, JSONB -> Types.VARCHAR;
            case BPCHAR -> Types.CHAR;
            case FLOAT4 -> Types.REAL;
            case FLOAT8 -> Types.DOUBLE;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMETZ -> Types.TIME_WITH_TIMEZONE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMPTZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            case NUMERIC -> Types.NUMERIC;
            default -> Types.OTHER;
        };
    }

    static String typeName(int oid) {
        return switch (oid) {
            case BOOL -> "bool";
            case BYTEA -> "bytea";
            case NAME -> "name";
            case INT8 -> "int8";
            case INT2 -> "int2";
            case INT4 -> "int4";
            case TEXT -> "text";
            case OID -> "oid";
            case JSON -> "json";
            case FLOAT4 -> "float4";
            case FLOAT8 -> "float8";
            case BPCHAR -> "bpchar";
            case VARCHAR -> "varchar";
            case DATE -> "date";
            case TIME -> "time";
            case TIMESTAMP -> "timestamp";
            case TIMESTAMPTZ -> "timestamptz";
            case TIMETZ -> "timetz";
            case NUMERIC -> "numeric";
            case UUID -> "uuid";
            case JSONB -> "jsonb";
            default -> "oid:" + oid;
        };
    }

    static String className(int oid) {
        return switch (oid) {
            case BOOL -> Boolean.class.getName();
            case BYTEA -> byte[].class.getName();
            case INT8, OID -> Long.class.getName();
            case INT2, INT4 -> Integer.class.getName();
            case FLOAT4 -> Float.class.getName();
            case FLOAT8 -> Double.class.getName();
            case NUMERIC -> BigDecimal.class.getName();
            case DATE -> java.sql.Date.class.getName();
            case TIME, TIMETZ -> java.sql.Time.class.getName();
            case TIMESTAMP, TIMESTAMPTZ -> java.sql.Timestamp.class.getName();
            case UUID -> java.util.UUID.class.getName();
            default -> String.class.getName();
        };
    }

    static boolean isNumeric(int oid) {
        return switch (oid) {
            case INT2, INT4, INT8, OID, FLOAT4, FLOAT8, NUMERIC -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- text to Java

    static boolean parseBoolean(String s) throws SQLException {
        return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "t", "true", "1", "y", "yes", "on" -> true;
            case "f", "false", "0", "n", "no", "off" -> false;
            default -> throw bad(s, "boolean");
        };
    }

    static long parseLong(String s, long min, long max, String type) throws SQLException {
        long v;
        try {
            v = Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            try {
                // Like pgjdbc: a decimal value is truncated toward zero.
                v = new BigDecimal(s.strip()).toBigInteger().longValueExact();
            } catch (NumberFormatException | ArithmeticException e2) {
                throw bad(s, type);
            }
        }
        if (v < min || v > max) throw bad(s, type);
        return v;
    }

    static double parseDouble(String s) throws SQLException {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            throw bad(s, "double");
        }
    }

    static BigDecimal parseBigDecimal(String s) throws SQLException {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            throw bad(s, "BigDecimal");
        }
    }

    /** {@code bytea} hex output: {@code \x0a0b...}. */
    static byte[] parseBytea(byte[] text) throws SQLException {
        if (text.length >= 2 && text[0] == '\\' && text[1] == 'x') {
            int n = (text.length - 2) / 2;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                int hi = Character.digit(text[2 + 2 * i], 16);
                int lo = Character.digit(text[3 + 2 * i], 16);
                if (hi < 0 || lo < 0) throw bad("bytea", "byte[]");
                out[i] = (byte) ((hi << 4) | lo);
            }
            return out;
        }
        throw new SQLException("bytea is not in hex format (set bytea_output = 'hex')", "22P02");
    }

    static LocalDate parseDate(String s) throws SQLException {
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw bad(s, "date");
        }
    }

    static LocalTime parseTime(String s) throws SQLException {
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetTime.parse(s, TIMETZ_TEXT).toLocalTime();
            } catch (DateTimeParseException e2) {
                throw bad(s, "time");
            }
        }
    }

    static LocalDateTime parseTimestamp(String s) throws SQLException {
        try {
            return LocalDateTime.parse(s, TIMESTAMP_TEXT);
        } catch (DateTimeParseException e) {
            if (s.length() == 10) return parseDate(s).atStartOfDay();
            throw bad(s, "timestamp");
        }
    }

    static OffsetDateTime parseTimestampTz(String s) throws SQLException {
        try {
            return OffsetDateTime.parse(s, TIMESTAMPTZ_TEXT);
        } catch (DateTimeParseException e) {
            throw bad(s, "timestamptz");
        }
    }

    private static SQLException bad(String value, String type) {
        // The value is left out of the message: it may be sensitive.
        return new SQLException("value is not a valid " + type, "22P02");
    }

    // ---------------------------------------------------------------- Java to text

    static String byteaHex(byte[] b) {
        StringBuilder sb = new StringBuilder(2 + b.length * 2).append("\\x");
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static String timestampText(LocalDateTime t) {
        return t.format(TIMESTAMP_TEXT);
    }

    private PgTypes() {}
}
