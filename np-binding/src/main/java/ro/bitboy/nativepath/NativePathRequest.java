package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A GET request with extra headers. Header values for secrets come from {@link NativeSecret}
 * (native memory), never from a Java {@code String}, so a token read from a vault, file or
 * another response can be sent without touching the JVM heap. The native side copies the value
 * into its own wiped buffer and marks the header sensitive.
 *
 * <p>The URL is still a Java {@code String}: do not put secrets in the URL or query string.
 */
public final class NativePathRequest implements AutoCloseable {

    private final String url;
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private NativePathRequest(String url) {
        this.url = url;
        this.handle = ForeignBridge.requestNew();
    }

    public static NativePathRequest get(String url) {
        return new NativePathRequest(url);
    }

    /** A request with any HTTP method ({@code POST}, {@code PUT}, {@code DELETE}, ...). */
    public static NativePathRequest of(String method, String url) {
        return new NativePathRequest(url).method(method);
    }

    public NativePathRequest method(String method) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            if (ForeignBridge.requestMethod(handle, arena.allocateFrom(method, StandardCharsets.UTF_8)) != 0) {
                throw new IllegalArgumentException("invalid method: " + method);
            }
        }
        return this;
    }

    /**
     * Sets the body from a Java array. The array is already on the heap, so use this only for
     * non-secret data; the native copy is wiped when freed.
     */
    public NativePathRequest body(byte[] bytes) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Math.max(bytes.length, 1));
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, 0, bytes.length);
            int rc = ForeignBridge.requestBody(handle, seg, bytes.length, false);
            seg.fill((byte) 0); // the arena does not wipe its scratch copy
            if (rc != 0) {
                throw new IllegalArgumentException("invalid body");
            }
        }
        return this;
    }

    /**
     * Sets a secret body taken from native memory, never from a Java array. The request is sent
     * on a connection of its own, closed afterwards, so the body does not linger in a reused buffer.
     */
    public NativePathRequest secretBody(NativeSecret body) {
        return nativeBody(body, true);
    }

    /** A body from native memory; {@code sensitive=false} lets a request without secrets use a pooled connection. */
    NativePathRequest nativeBody(NativeSecret body, boolean sensitive) {
        checkOpen();
        if (ForeignBridge.requestBody(handle, body.asMemorySegment(), body.length(), sensitive) != 0) {
            throw new IllegalArgumentException("invalid body");
        }
        return this;
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("NativePathRequest is closed");
        }
    }

    public String url() {
        return url;
    }

    /** Adds a non-secret header (the value is an ordinary Java string). */
    public NativePathRequest header(String name, String value) {
        return add(name, value, null, false);
    }

    /** Adds a sensitive header whose whole value is the secret. */
    public NativePathRequest secretHeader(String name, NativeSecret value) {
        return add(name, null, value, true);
    }

    /** Adds a sensitive header made of a plain prefix (such as {@code "Bearer "}) and a secret. */
    public NativePathRequest secretHeader(String name, String prefix, NativeSecret value) {
        return add(name, prefix, value, true);
    }

    private NativePathRequest add(String name, String prefix, NativeSecret secret, boolean sensitive) {
        if (closed.get()) {
            throw new IllegalStateException("NativePathRequest is closed");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cName = arena.allocateFrom(name, StandardCharsets.UTF_8);
            byte[] p = prefix == null ? new byte[0] : prefix.getBytes(StandardCharsets.UTF_8);
            MemorySegment cPrefix = p.length == 0 ? MemorySegment.NULL : arena.allocate(p.length);
            if (p.length > 0) {
                MemorySegment.copy(MemorySegment.ofArray(p), 0, cPrefix, 0, p.length);
            }
            MemorySegment value = MemorySegment.NULL;
            long valueLen = 0;
            if (secret != null) {
                value = secret.asMemorySegment();
                valueLen = secret.length();
            }
            int rc = ForeignBridge.requestHeader(handle, cName, cPrefix, p.length, value, valueLen, sensitive);
            if (rc != 0) {
                throw new IllegalArgumentException("invalid header: " + name);
            }
        }
        return this;
    }

    MemorySegment handle() {
        return handle;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ForeignBridge.requestFree(handle);
        }
    }
}
