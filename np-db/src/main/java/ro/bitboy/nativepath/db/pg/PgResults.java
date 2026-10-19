package ro.bitboy.nativepath.db.pg;

import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Native results of one call: one entry per statement. Cell bytes stay in native memory and are
 * read in place; closing wipes and frees them.
 */
final class PgResults implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    /** One statement's outcome. Column names and the command tag are copied to the heap (they are not data). */
    record Result(String[] names, int[] typeOids, long nrows, MemorySegment cells, MemorySegment data,
                  long rowsAffected, boolean hasRows, String tag) {

        int ncols() {
            return names.length;
        }

        /** Byte length of a cell, or -1 for NULL. */
        long length(long row, int col) {
            return cells.get(JAVA_LONG, (row * names.length + col) * PgNative.CELL_SIZE + 8);
        }

        long offset(long row, int col) {
            return cells.get(JAVA_LONG, (row * names.length + col) * PgNative.CELL_SIZE);
        }
    }

    private record Free(MemorySegment handle) implements Runnable {
        @Override
        public void run() {
            PgNative.resultsFree(handle);
        }
    }

    private final List<Result> results;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Cleaner.Cleanable cleanable;

    PgResults(MemorySegment handle) {
        this.cleanable = CLEANER.register(this, new Free(handle));
        long n = PgNative.resultsCount(handle);
        List<Result> list = new ArrayList<>((int) n);
        for (long i = 0; i < n; i++) {
            list.add(read(PgNative.resultsGet(handle, i)));
        }
        this.results = list;
    }

    private static Result read(MemorySegment r) {
        long ncols = r.get(JAVA_LONG, 8);
        MemorySegment cols = r.get(ADDRESS, 0).reinterpret(ncols * PgNative.COLUMN.byteSize());
        String[] names = new String[(int) ncols];
        int[] types = new int[(int) ncols];
        for (int c = 0; c < ncols; c++) {
            long base = c * PgNative.COLUMN.byteSize();
            MemorySegment namePtr = cols.get(ADDRESS, base);
            long nameLen = cols.get(JAVA_LONG, base + 8);
            names[c] = utf8(namePtr, nameLen);
            types[c] = cols.get(JAVA_INT, base + 16);
        }
        long nrows = r.get(JAVA_LONG, 24);
        MemorySegment cells = r.get(ADDRESS, 16).reinterpret(nrows * ncols * PgNative.CELL_SIZE);
        MemorySegment data = r.get(ADDRESS, 32).reinterpret(r.get(JAVA_LONG, 40));
        long affected = r.get(JAVA_LONG, 48);
        boolean hasRows = r.get(JAVA_INT, 56) != 0;
        String tag = utf8(r.get(ADDRESS, 64), r.get(JAVA_LONG, 72));
        return new Result(names, types, nrows, cells, data, affected, hasRows, tag);
    }

    private static String utf8(MemorySegment ptr, long len) {
        if (len == 0) return "";
        return new String(ptr.reinterpret(len).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    List<Result> results() {
        return results;
    }

    boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanable.clean();
        }
    }
}
