package ro.bitboy.nativepath.db.pg;

import java.lang.foreign.MemorySegment;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;

/** Turns a native {@code WkDbError} into the matching {@link SQLException} subclass. */
final class PgErrors {

    /** Reads and frees {@code err}. The server's DETAIL is included only when {@code includeDetail}. */
    static SQLException take(MemorySegment err, boolean includeDetail) {
        MemorySegment e = err.reinterpret(PgNative.ERROR.byteSize());
        try {
            int kind = e.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
            String sqlstate = PgNative.cString(e.get(java.lang.foreign.ValueLayout.ADDRESS, 8));
            String message = PgNative.cString(e.get(java.lang.foreign.ValueLayout.ADDRESS, 16));
            String detail = includeDetail ? PgNative.cString(e.get(java.lang.foreign.ValueLayout.ADDRESS, 24)) : null;
            if (detail != null) message = message + "\n  Detail: " + detail;
            SQLException ex = create(message, sqlstate);
            return kind == PgNative.KIND_IO || kind == PgNative.KIND_PROTOCOL
                    ? new SQLNonTransientConnectionException(message, sqlstate, ex) : ex;
        } finally {
            PgNative.errorFree(err);
        }
    }

    static SQLException create(String message, String sqlstate) {
        String cls = sqlstate == null || sqlstate.length() < 2 ? "" : sqlstate.substring(0, 2);
        return switch (cls) {
            case "08" -> new SQLNonTransientConnectionException(message, sqlstate);
            case "23" -> new SQLIntegrityConstraintViolationException(message, sqlstate);
            case "28" -> new SQLInvalidAuthorizationSpecException(message, sqlstate);
            case "40" -> new SQLTransactionRollbackException(message, sqlstate);
            case "42" -> new SQLSyntaxErrorException(message, sqlstate);
            default -> new SQLException(message, sqlstate);
        };
    }

    private PgErrors() {}
}
