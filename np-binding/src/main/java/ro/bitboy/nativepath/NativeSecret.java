package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An off-heap, memory-isolated buffer holding sensitive data (tokens, keys, bodies).
 * When closed, the underlying physical memory is volatile-wiped with zeroes in hardware RAM
 * via Rust's zeroize engine and freed immediately, preventing any retention on the JVM heap.
 */
public final class NativeSecret implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final long address;
    private long length;
    private final long capacity;
    private MemorySegment segment;
    private final boolean heapCopiesAllowed;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Cleaner.Cleanable cleanable;

    private static record CleanupAction(long address, long length, long capacity) implements Runnable {
        @Override
        public void run() {
            if (address != 0 && capacity > 0) {
                ForeignBridge.wipeAndFree(address, length, capacity);
            }
        }
    }

    public NativeSecret(long address, long length, long capacity) {
        this(address, length, capacity, true);
    }

    /**
     * @param heapCopiesAllowed {@code false} in secret mode: {@link #toByteArray()} and
     *                          {@link #toUtf8String()} then throw, so the data stays off the JVM heap.
     */
    public NativeSecret(long address, long length, long capacity, boolean heapCopiesAllowed) {
        this.heapCopiesAllowed = heapCopiesAllowed;
        this.address = address;
        this.length = length;
        this.capacity = capacity;
        if (address != 0 && length > 0) {
            this.segment = MemorySegment.ofAddress(address).reinterpret(length);
        } else {
            this.segment = MemorySegment.NULL;
        }
        this.cleanable = CLEANER.register(this, new CleanupAction(address, length, capacity));
    }

    /**
     * Returns the direct off-heap MemorySegment.
     * Guaranteed bounds-checked; out-of-bounds access throws IndexOutOfBoundsException.
     */
    public MemorySegment asMemorySegment() {
        checkNotClosed();
        return segment;
    }

    public long length() {
        checkNotClosed();
        return length;
    }

    /**
     * Reads the secret as bytes. Use with caution, as byte[] enters the JVM heap.
     */
    public byte[] toByteArray() {
        checkNotClosed();
        checkHeapCopyAllowed();
        if (length == 0) return new byte[0];
        byte[] bytes = new byte[(int) length];
        MemorySegment.copy(segment, 0, MemorySegment.ofArray(bytes), 0, length);
        return bytes;
    }

    /**
     * Reads as UTF-8 String. WARNING: In standard Java this will intern or allocate
     * a String on the JVM heap. Preferred only when non-sensitive or exiting the nativepath.
     */
    public String toUtf8String() {
        return new String(toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Allocates a zeroed, writable native secret of {@code length} bytes. Fill it through
     * {@link #asMemorySegment()} (for example straight from a file channel) so the value never
     * has to exist as a Java array or String.
     */
    public static NativeSecret allocate(long length) {
        return allocate(length, true);
    }

    /**
     * Allocates a zeroed, writable native secret. With {@code heapCopiesAllowed == false} the result
     * refuses {@link #toByteArray()} and {@link #toUtf8String()}, as values from secret sources do.
     */
    public static NativeSecret allocate(long length, boolean heapCopiesAllowed) {
        if (length <= 0) {
            return new NativeSecret(0, 0, 0, heapCopiesAllowed);
        }
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment outCap = arena.allocate(java.lang.foreign.ValueLayout.JAVA_LONG);
            MemorySegment ptr = ForeignBridge.secretAlloc(length, outCap);
            if (ptr.equals(MemorySegment.NULL)) {
                throw new OutOfMemoryError("native secret allocation failed");
            }
            return new NativeSecret(ptr.address(), length,
                    outCap.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0), heapCopiesAllowed);
        }
    }

    /** Size of the underlying native allocation, which is wiped in full when the secret is closed. */
    public long capacityBytes() {
        return capacity;
    }

    /** Shrinks the visible length (the whole allocation is still wiped on close). */
    void truncate(long newLength) {
        if (newLength < 0 || newLength > capacity) {
            throw new IllegalArgumentException("length out of range");
        }
        this.length = newLength;
        this.segment = newLength > 0 ? MemorySegment.ofAddress(address).reinterpret(newLength) : MemorySegment.NULL;
    }

    /**
     * Reads a secret file (for example a mounted Kubernetes or Docker secret) straight into native
     * memory. The bytes go from the file channel into the native buffer through a direct buffer, so
     * no Java array or string is created. A trailing newline, which editors and {@code echo} add, is
     * dropped. The result refuses heap copies.
     */
    public static NativeSecret fromFile(java.nio.file.Path path) throws java.io.IOException {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
            long size = ch.size();
            NativeSecret s = allocate(size, false);
            try {
                java.nio.ByteBuffer buf = s.asMemorySegment().asByteBuffer();
                while (buf.hasRemaining() && ch.read(buf) >= 0) { /* keep reading */ }
                long n = size;
                MemorySegment seg = s.asMemorySegment();
                if (n > 0 && seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, n - 1) == '\n') n--;
                if (n > 0 && seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, n - 1) == '\r') n--;
                s.truncate(n);
                return s;
            } catch (java.io.IOException | RuntimeException e) {
                s.close();
                throw e;
            }
        }
    }

    /** True when copies onto the JVM heap are permitted for this secret. */
    public boolean heapCopiesAllowed() {
        return heapCopiesAllowed;
    }

    private void checkHeapCopyAllowed() {
        if (!heapCopiesAllowed) {
            throw new IllegalStateException(
                    "secret mode: copying this value to the JVM heap is disabled; use asMemorySegment()");
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("NativeSecret has already been closed and wiped!");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanable.clean();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
