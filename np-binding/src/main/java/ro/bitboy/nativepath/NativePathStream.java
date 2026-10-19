package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A response body consumed incrementally from native memory.
 *
 * <p>Each chunk is the network buffer produced by the Rust engine, exposed without copying.
 * Only one chunk is alive at a time, so memory use stays flat whatever the body size. The
 * previous chunk is wiped when the next one is requested, and the rest when the stream is
 * closed. Use {@link #nextChunk()} to stay off the JVM heap; the {@link InputStream} methods
 * copy into caller-supplied {@code byte[]}s and therefore put data on the heap.
 *
 * <p>Not thread-safe. Always close it (try-with-resources); a {@link Cleaner} is only a backstop.
 */
public final class NativePathStream extends InputStream {

    private static final Cleaner CLEANER = Cleaner.create();

    private static final class Native implements Runnable {
        private final MemorySegment handle;
        private final AtomicBoolean freed = new AtomicBoolean(false);

        Native(MemorySegment handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            if (freed.compareAndSet(false, true)) {
                ForeignBridge.streamFree(handle);
            }
        }
    }

    private final MemorySegment handle;
    private final Native nativeState;
    private final Cleaner.Cleanable cleanable;
    private final int statusCode;
    private final long contentLength;
    private final boolean secretMode;
    // Receives the (pointer, length) pair of each chunk; holds no secret data.
    private final MemorySegment out = Arena.ofAuto().allocate(16, 8);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private MemorySegment chunk = MemorySegment.NULL;
    private long chunkPos;
    private boolean eof;
    // Total bytes handed out by nextChunk(); sizes readNBytes from the declared length.
    private long delivered;
    // Position saved by mark(): the chunk it was in (by delivered count) and the offset in it.
    private long markDelivered = -1;
    private long markPos;
    private boolean markAtChunkEnd;

    NativePathStream(MemorySegment handle, int statusCode, long contentLength, boolean secretMode) {
        this.secretMode = secretMode;
        this.handle = handle;
        this.statusCode = statusCode;
        this.contentLength = contentLength;
        this.nativeState = new Native(handle);
        this.cleanable = CLEANER.register(this, nativeState);
    }

    /** Borrowed view of the response headers ({@code name\0value\0} pairs); valid until close. */
    private MemorySegment headerBlock() {
        if (closed.get()) {
            throw new IllegalStateException("NativePathStream is closed");
        }
        MemorySegment lenOut = Arena.ofAuto().allocate(8, 8);
        MemorySegment ptr = ForeignBridge.streamHeaders(handle, lenOut);
        long len = lenOut.get(ValueLayout.JAVA_LONG, 0);
        return ptr.equals(MemorySegment.NULL) || len == 0 ? MemorySegment.NULL : ptr.reinterpret(len);
    }

    private static long indexOfNul(MemorySegment seg, long from) {
        long i = from;
        while (seg.get(ValueLayout.JAVA_BYTE, i) != 0) i++;
        return i;
    }

    /** Names of the response headers (lower case, repeated for repeated headers). Names are not secret. */
    public java.util.List<String> headerNames() {
        MemorySegment h = headerBlock();
        java.util.List<String> names = new java.util.ArrayList<>();
        long n = h.byteSize(), i = 0;
        while (i < n) {
            long e = indexOfNul(h, i);
            names.add(h.getString(i, java.nio.charset.StandardCharsets.UTF_8));
            i = indexOfNul(h, e + 1) + 1;
        }
        return names;
    }

    /**
     * Copies the first value of the named header (case-insensitive) into a new native
     * {@link NativeSecret}, without creating a Java string. Returns {@code null} if absent.
     * Use this for headers that carry secrets, such as {@code Set-Cookie}.
     */
    public NativeSecret headerSecret(String name) {
        MemorySegment h = headerBlock();
        long n = h.byteSize(), i = 0;
        while (i < n) {
            long ne = indexOfNul(h, i);
            long ve = indexOfNul(h, ne + 1);
            if (nameMatches(h, i, ne, name)) {
                long len = ve - (ne + 1);
                NativeSecret s = NativeSecret.allocate(len, !secretMode);
                if (len > 0) MemorySegment.copy(h, ne + 1, s.asMemorySegment(), 0, len);
                return s;
            }
            i = ve + 1;
        }
        return null;
    }

    private static boolean nameMatches(MemorySegment h, long from, long to, String name) {
        if (to - from != name.length()) return false;
        for (int k = 0; k < name.length(); k++) {
            if (Character.toLowerCase((char) h.get(ValueLayout.JAVA_BYTE, from + k)) != Character.toLowerCase(name.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    /**
     * All response headers as Java strings (on the heap). Not available in secret mode: use
     * {@link #headerNames()} and {@link #headerSecret(String)} instead.
     */
    public java.util.Map<String, java.util.List<String>> headers() {
        if (secretMode) {
            throw new IllegalStateException(
                    "secret mode: header values are not available as Java strings; use headerSecret(name)");
        }
        MemorySegment h = headerBlock();
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        long n = h.byteSize(), i = 0;
        while (i < n) {
            long ne = indexOfNul(h, i);
            long ve = indexOfNul(h, ne + 1);
            out.computeIfAbsent(h.getString(i, java.nio.charset.StandardCharsets.UTF_8), k -> new java.util.ArrayList<>())
                    .add(h.getString(ne + 1, java.nio.charset.StandardCharsets.ISO_8859_1));
            i = ve + 1;
        }
        return out;
    }

    public boolean isSecretMode() {
        return secretMode;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Declared body length, or -1 if the server did not send one. */
    public long contentLength() {
        return contentLength;
    }

    /**
     * Returns the next chunk as an off-heap segment, or {@code null} at end of body. The segment
     * is valid only until the next call to this method or {@link #close()}; do not retain it.
     */
    public MemorySegment nextChunk() {
        if (closed.get()) {
            throw new IllegalStateException("NativePathStream is closed");
        }
        if (eof) {
            return null;
        }
        int rc = ForeignBridge.streamNext(handle, out, out.asSlice(8));
        if (rc < 0) {
            MemorySegment msg = ForeignBridge.streamError(handle);
            String text = msg.equals(MemorySegment.NULL)
                    ? "unknown error"
                    : msg.reinterpret(Long.MAX_VALUE).getString(0);
            throw new IllegalStateException("NativePath stream error: " + text);
        }
        if (rc == 0) {
            eof = true;
            chunk = MemorySegment.NULL;
            return null;
        }
        MemorySegment ptr = out.get(ValueLayout.ADDRESS, 0);
        long len = out.get(ValueLayout.JAVA_LONG, 8);
        delivered += len;
        return ptr.reinterpret(len);
    }

    /**
     * Reads the rest of the body into one native {@link NativeSecret}, assembled without any
     * heap array (chunks are copied native to native). Fails if the body exceeds {@code maxBytes}.
     * Intended for small secret documents such as token responses.
     */
    public NativeSecret readToSecret(long maxBytes) {
        long declared = contentLength;
        if (declared > maxBytes) {
            throw new IllegalStateException("response body of " + declared + " bytes exceeds " + maxBytes);
        }
        NativeSecret out = NativeSecret.allocate(Math.max(declared, 0), !secretMode);
        long used = 0;
        try {
            MemorySegment seg;
            while ((seg = nextChunk()) != null) {
                long need = used + seg.byteSize();
                if (need > maxBytes) {
                    throw new IllegalStateException("response body exceeds " + maxBytes + " bytes");
                }
                if (need > out.capacityBytes()) {
                    NativeSecret bigger = NativeSecret.allocate(Math.min(maxBytes, Math.max(need, out.capacityBytes() * 2)), !secretMode);
                    MemorySegment.copy(out.asMemorySegment(), 0, bigger.asMemorySegment(), 0, used);
                    out.close();
                    out = bigger;
                }
                MemorySegment.copy(seg, 0, out.asMemorySegment(), used, seg.byteSize());
                used = need;
            }
            out.truncate(used);
            return out;
        } catch (RuntimeException e) {
            out.close();
            throw e;
        }
    }

    private void checkHeapReadsAllowed() throws IOException {
        if (secretMode) {
            throw new IOException("secret mode: reading into a Java array is disabled; use nextChunk()");
        }
    }

    /** Makes {@code chunk} hold unread bytes; returns false at end of body. */
    private boolean fill() throws IOException {
        try {
            while (chunkPos >= chunk.byteSize()) {
                MemorySegment next = nextChunk();
                if (next == null) {
                    return false;
                }
                chunk = next;
                chunkPos = 0;
            }
            return true;
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public int read() throws IOException {
        checkHeapReadsAllowed();
        if (!fill()) {
            return -1;
        }
        return chunk.get(ValueLayout.JAVA_BYTE, chunkPos++) & 0xFF;
    }

    /**
     * With a declared length, allocates the result once at its exact size and copies each chunk
     * straight into it. The {@link InputStream} default reads into 16 KB buffers and then copies
     * them all again into the result. Spring's {@code byte[]} converter calls this.
     */
    @Override
    public byte[] readNBytes(int len) throws IOException {
        if (len < 0) {
            throw new IllegalArgumentException("len < 0");
        }
        checkHeapReadsAllowed();
        if (contentLength < 0) {
            return super.readNBytes(len);
        }
        long unread = Math.max(0, chunk.byteSize() - chunkPos);
        long remaining = Math.max(0, contentLength - delivered) + unread;
        int size = (int) Math.min(len, remaining);
        byte[] result = new byte[size];
        int n = readNBytes(result, 0, size);
        if (n < size) {
            return java.util.Arrays.copyOf(result, n);
        }
        if (size == len) {
            return result;
        }
        // The body was longer than declared; append whatever follows.
        byte[] rest = super.readNBytes(len - size);
        if (rest.length == 0) {
            return result;
        }
        byte[] joined = java.util.Arrays.copyOf(result, size + rest.length);
        System.arraycopy(rest, 0, joined, size, rest.length);
        return joined;
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        return readNBytes(Integer.MAX_VALUE);
    }

    /**
     * Supported within the current native chunk: {@link #reset()} succeeds if the bytes read since
     * {@code mark} stayed in that chunk, or (when marked at its end) in the first part of the next
     * one. Reading a single byte can always be reset. Otherwise {@code reset} throws; it never
     * returns wrong data. This lets Spring peek for an empty body without wrapping the stream in a
     * {@code PushbackInputStream}, which would hide {@link #readNBytes(int)}.
     */
    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int readlimit) {
        markDelivered = delivered;
        markPos = chunkPos;
        markAtChunkEnd = chunkPos >= chunk.byteSize();
    }

    @Override
    public void reset() throws IOException {
        if (markDelivered < 0) {
            throw new IOException("mark() was not called");
        }
        if (delivered == markDelivered) {
            chunkPos = markPos;
        } else if (markAtChunkEnd && delivered == markDelivered + chunk.byteSize()) {
            chunkPos = 0;
        } else {
            throw new IOException("mark invalidated: reads since mark() crossed a native chunk boundary");
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkHeapReadsAllowed();
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int n = (int) Math.min(len, chunk.byteSize() - chunkPos);
        MemorySegment.copy(chunk, chunkPos, MemorySegment.ofArray(b), off, n);
        chunkPos += n;
        return n;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            chunk = MemorySegment.NULL;
            cleanable.clean();
        }
    }
}
