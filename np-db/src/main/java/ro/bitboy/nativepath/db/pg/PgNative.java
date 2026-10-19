package ro.bitboy.nativepath.db.pg;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Panama bindings for the {@code np_db_*} functions in the shared native library. */
final class PgNative {

    /** {@code WkDbParam}: ptr, len (-1 = NULL), type oid. */
    static final StructLayout PARAM = MemoryLayout.structLayout(
            ADDRESS.withName("ptr"), JAVA_LONG.withName("len"), JAVA_INT.withName("type_oid"), JAVA_INT.withName("pad"));

    /** {@code WkDbColumn}. */
    static final StructLayout COLUMN = MemoryLayout.structLayout(
            ADDRESS.withName("name_ptr"), JAVA_LONG.withName("name_len"), JAVA_INT.withName("type_oid"), JAVA_INT.withName("table_oid"));

    /** {@code WkDbResult}. */
    static final StructLayout RESULT = MemoryLayout.structLayout(
            ADDRESS.withName("columns"), JAVA_LONG.withName("ncols"),
            ADDRESS.withName("cells"), JAVA_LONG.withName("nrows"),
            ADDRESS.withName("data"), JAVA_LONG.withName("data_len"),
            JAVA_LONG.withName("rows_affected"), JAVA_INT.withName("has_rows"), JAVA_INT.withName("pad"),
            ADDRESS.withName("tag_ptr"), JAVA_LONG.withName("tag_len"));

    /** {@code Cell}: offset into the data block, length (-1 = NULL). */
    static final long CELL_SIZE = 16;

    /** {@code WkDbError}. */
    static final StructLayout ERROR = MemoryLayout.structLayout(
            JAVA_INT.withName("kind"), JAVA_INT.withName("pad"),
            ADDRESS.withName("sqlstate"), ADDRESS.withName("message"), ADDRESS.withName("detail"));

    static final int KIND_IO = 1;
    static final int KIND_PROTOCOL = 2;

    private static final MethodHandle CONNECT;
    private static final MethodHandle CLOSE;
    private static final MethodHandle EXECUTE;
    private static final MethodHandle SIMPLE;
    private static final MethodHandle TX_STATUS;
    private static final MethodHandle PARAMETER;
    private static final MethodHandle SET_TIMEOUT;
    private static final MethodHandle RESULTS_COUNT;
    private static final MethodHandle RESULTS_GET;
    private static final MethodHandle RESULTS_FREE;
    private static final MethodHandle ERROR_FREE;
    private static final MethodHandle SECURITY;
    private static final MethodHandle CANCEL_TOKEN;
    private static final MethodHandle CANCEL;
    private static final MethodHandle CANCEL_TOKEN_FREE;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = ForeignBridge.library();
        CONNECT = bind(linker, lib, "np_db_pg_connect", FunctionDescriptor.of(ADDRESS,
                ADDRESS, JAVA_SHORT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT,
                JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        CLOSE = bind(linker, lib, "np_db_pg_close", FunctionDescriptor.ofVoid(ADDRESS));
        EXECUTE = bind(linker, lib, "np_db_pg_execute", FunctionDescriptor.of(ADDRESS,
                ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
        SIMPLE = bind(linker, lib, "np_db_pg_simple", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        TX_STATUS = bind(linker, lib, "np_db_pg_tx_status", FunctionDescriptor.of(JAVA_BYTE, ADDRESS));
        PARAMETER = bind(linker, lib, "np_db_pg_parameter", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        SET_TIMEOUT = bind(linker, lib, "np_db_pg_set_timeout", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_INT));
        RESULTS_COUNT = bind(linker, lib, "np_db_results_count", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        RESULTS_GET = bind(linker, lib, "np_db_results_get", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        RESULTS_FREE = bind(linker, lib, "np_db_results_free", FunctionDescriptor.ofVoid(ADDRESS));
        ERROR_FREE = bind(linker, lib, "np_db_error_free", FunctionDescriptor.ofVoid(ADDRESS));
        SECURITY = bind(linker, lib, "np_db_pg_security", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        CANCEL_TOKEN = bind(linker, lib, "np_db_pg_cancel_token", FunctionDescriptor.of(ADDRESS, ADDRESS));
        CANCEL = bind(linker, lib, "np_db_pg_cancel", FunctionDescriptor.of(ADDRESS, ADDRESS));
        CANCEL_TOKEN_FREE = bind(linker, lib, "np_db_pg_cancel_token_free", FunctionDescriptor.ofVoid(ADDRESS));
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError(name + " not found; rebuild the native library")), fd);
    }

    static MemorySegment connect(MemorySegment host, int port, MemorySegment user, MemorySegment database,
                                 MemorySegment app, MemorySegment password, long passwordLen, int timeoutMs,
                                 int sslMode, MemorySegment sslRootCert, int channelBinding, MemorySegment outErr) {
        try {
            return (MemorySegment) CONNECT.invokeExact(host, (short) port, user, database, app, password, passwordLen, timeoutMs,
                    sslMode, sslRootCert, channelBinding, outErr);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_connect", t);
        }
    }

    static void close(MemorySegment conn) {
        try {
            CLOSE.invokeExact(conn);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_close", t);
        }
    }

    static MemorySegment execute(MemorySegment conn, MemorySegment sql, long sqlLen, MemorySegment params, long n,
                                 MemorySegment outErr) {
        try {
            return (MemorySegment) EXECUTE.invokeExact(conn, sql, sqlLen, params, n, outErr);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_execute", t);
        }
    }

    static MemorySegment simple(MemorySegment conn, MemorySegment sql, long sqlLen, MemorySegment outErr) {
        try {
            return (MemorySegment) SIMPLE.invokeExact(conn, sql, sqlLen, outErr);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_simple", t);
        }
    }

    static byte txStatus(MemorySegment conn) {
        try {
            return (byte) TX_STATUS.invokeExact(conn);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_tx_status", t);
        }
    }

    /** A server parameter (such as {@code server_version}), or null. */
    static String parameter(MemorySegment conn, MemorySegment name) {
        MemorySegment p;
        try {
            p = (MemorySegment) PARAMETER.invokeExact(conn, name);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_parameter", t);
        }
        if (p.equals(MemorySegment.NULL)) return null;
        try {
            return p.reinterpret(Long.MAX_VALUE).getString(0);
        } finally {
            ForeignBridge.stringFree(p);
        }
    }

    static boolean setTimeout(MemorySegment conn, int ms) {
        try {
            return (boolean) SET_TIMEOUT.invokeExact(conn, ms);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_set_timeout", t);
        }
    }

    static long resultsCount(MemorySegment results) {
        try {
            return (long) RESULTS_COUNT.invokeExact(results);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_results_count", t);
        }
    }

    static MemorySegment resultsGet(MemorySegment results, long i) {
        try {
            return ((MemorySegment) RESULTS_GET.invokeExact(results, i)).reinterpret(RESULT.byteSize());
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_results_get", t);
        }
    }

    static void resultsFree(MemorySegment results) {
        try {
            RESULTS_FREE.invokeExact(results);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_results_free", t);
        }
    }

    static void errorFree(MemorySegment err) {
        try {
            ERROR_FREE.invokeExact(err);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_error_free", t);
        }
    }

    /** Bit 0: encrypted. Bit 1: authentication was bound to the TLS channel. */
    static int security(MemorySegment conn) {
        try {
            return (int) SECURITY.invokeExact(conn);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_security", t);
        }
    }

    static MemorySegment cancelToken(MemorySegment conn) {
        try {
            return (MemorySegment) CANCEL_TOKEN.invokeExact(conn);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_cancel_token", t);
        }
    }

    /** NULL on success, else an error to pass to {@link PgErrors#take}. */
    static MemorySegment cancel(MemorySegment token) {
        try {
            return (MemorySegment) CANCEL.invokeExact(token);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_cancel", t);
        }
    }

    static void cancelTokenFree(MemorySegment token) {
        try {
            CANCEL_TOKEN_FREE.invokeExact(token);
        } catch (Throwable t) {
            throw new IllegalStateException("np_db_pg_cancel_token_free", t);
        }
    }

    static String cString(MemorySegment p) {
        return p.equals(MemorySegment.NULL) ? null : p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private PgNative() {}
}
