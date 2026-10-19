package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativePathHttpClient implements AutoCloseable {

    private final MemorySegment clientHandle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean secretMode;

    private NativePathHttpClient(MemorySegment clientHandle, boolean secretMode) {
        this.clientHandle = clientHandle;
        this.secretMode = secretMode;
    }

    /**
     * True when this client keeps response data off the JVM heap: bodies are only available as
     * {@link NativeSecret} or native chunk segments, and every heap-copying accessor throws.
     */
    public boolean isSecretMode() {
        return secretMode;
    }

    public static NativePathHttpClient create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean trustSelfSigned = false;
        private long maxBodyBytes = -1;
        private boolean secretMode = false;
        private boolean isolateConnections = false;

        /**
         * Strict isolation: every request uses a connection of its own that is closed afterwards, so
         * no network buffer is ever reused. Costs a TCP (and TLS) handshake per request and can
         * exhaust ephemeral ports at high request rates. Requests that carry secret headers are
         * isolated like this regardless of the setting, because a kept-alive connection retains the
         * last request's header bytes in its write buffer.
         */
        public Builder isolateConnections(boolean enabled) {
            this.isolateConnections = enabled;
            return this;
        }

        /**
         * Secret mode: response data never reaches the JVM heap. {@code get} returns a body that
         * only offers {@link NativeSecret#asMemorySegment()}, streams refuse {@code InputStream}
         * reads, and {@code toByteArray()} / {@code toUtf8String()} throw.
         */
        public Builder secretMode(boolean enabled) {
            this.secretMode = enabled;
            return this;
        }

        /** Largest body accepted by {@link NativePathHttpClient#get}; bigger ones must use {@code getStream}. */
        public Builder maxBodyBytes(long max) {
            this.maxBodyBytes = max;
            return this;
        }

        public Builder trustSelfSigned(boolean trust) {
            this.trustSelfSigned = trust;
            return this;
        }

        public NativePathHttpClient build() {
            MemorySegment handle = ForeignBridge.clientCreate();
            if (handle.equals(MemorySegment.NULL)) {
                throw new RuntimeException("Failed to allocate native NativePath client");
            }
            if (trustSelfSigned) {
                ForeignBridge.clientSetInsecure(handle, true);
            }
            if (isolateConnections) {
                ForeignBridge.clientSetIsolateAll(handle, true);
            }
            if (maxBodyBytes >= 0) {
                ForeignBridge.clientSetMaxBody(handle, maxBodyBytes);
            }
            return new NativePathHttpClient(handle, secretMode);
        }
    }

    public void setTrustSelfSigned(boolean trust) {
        checkNotClosed();
        ForeignBridge.clientSetInsecure(clientHandle, trust);
    }

    public void setMaxBodyBytes(long max) {
        checkNotClosed();
        ForeignBridge.clientSetMaxBody(clientHandle, max);
    }

    /**
     * Opens a streaming GET: returns once the headers arrive; the body is read incrementally
     * and never fully held in memory. Close the stream when done.
     */
    public NativePathStream getStream(String url) {
        checkNotClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cUrl = arena.allocateFrom(url, StandardCharsets.UTF_8);
            MemorySegment outStatus = arena.allocate(ValueLayout.JAVA_SHORT);
            MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment outError = arena.allocate(ValueLayout.ADDRESS);

            MemorySegment stream = ForeignBridge.streamOpen(clientHandle, cUrl, outStatus, outLength, outError);
            if (stream.equals(MemorySegment.NULL)) {
                MemorySegment errPtr = outError.get(ValueLayout.ADDRESS, 0);
                String message = errPtr.equals(MemorySegment.NULL)
                        ? "unknown error"
                        : errPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
                if (!errPtr.equals(MemorySegment.NULL)) {
                    ForeignBridge.stringFree(errPtr);
                }
                throw new RuntimeException("NativePath error: " + message);
            }
            return new NativePathStream(stream,
                    Short.toUnsignedInt(outStatus.get(ValueLayout.JAVA_SHORT, 0)),
                    outLength.get(ValueLayout.JAVA_LONG, 0), secretMode);
        }
    }

    /** Opens a streaming GET with extra headers (see {@link NativePathRequest}). */
    public NativePathStream getStream(NativePathRequest request) {
        checkNotClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cUrl = arena.allocateFrom(request.url(), StandardCharsets.UTF_8);
            MemorySegment outStatus = arena.allocate(ValueLayout.JAVA_SHORT);
            MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment outError = arena.allocate(ValueLayout.ADDRESS);

            MemorySegment stream = ForeignBridge.streamOpenWith(clientHandle, cUrl, request.handle(),
                    outStatus, outLength, outError);
            if (stream.equals(MemorySegment.NULL)) {
                MemorySegment errPtr = outError.get(ValueLayout.ADDRESS, 0);
                String message = errPtr.equals(MemorySegment.NULL)
                        ? "unknown error"
                        : errPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
                if (!errPtr.equals(MemorySegment.NULL)) {
                    ForeignBridge.stringFree(errPtr);
                }
                throw new RuntimeException("NativePath error: " + message);
            }
            return new NativePathStream(stream,
                    Short.toUnsignedInt(outStatus.get(ValueLayout.JAVA_SHORT, 0)),
                    outLength.get(ValueLayout.JAVA_LONG, 0), secretMode);
        }
    }

    public NativePathResponse get(String url) {
        checkNotClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cUrl = arena.allocateFrom(url, StandardCharsets.UTF_8);
            MemorySegment rawResponsePtr = ForeignBridge.nativePathGet(clientHandle, cUrl);

            if (rawResponsePtr.equals(MemorySegment.NULL)) {
                throw new RuntimeException("Native request returned null pointer");
            }

            MemorySegment responseStruct = rawResponsePtr.reinterpret(ForeignBridge.RESPONSE_LAYOUT.byteSize());

            short status = responseStruct.get(ValueLayout.JAVA_SHORT, 0);
            MemorySegment bodyPtr = responseStruct.get(ValueLayout.ADDRESS, 8);
            long bodyLen = responseStruct.get(ValueLayout.JAVA_LONG, 16);
            long bodyCap = responseStruct.get(ValueLayout.JAVA_LONG, 24);
            MemorySegment errorMsgPtr = responseStruct.get(ValueLayout.ADDRESS, 32);

            if (!errorMsgPtr.equals(MemorySegment.NULL)) {
                String error = errorMsgPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
                ForeignBridge.responseFree(rawResponsePtr);
                throw new RuntimeException("NativePath error (" + status + "): " + error);
            }

            NativeSecret secret = new NativeSecret(bodyPtr.address(), bodyLen, bodyCap, !secretMode);
            // The secret now owns the body; release the small response struct (it used to leak).
            ForeignBridge.responseShellFree(rawResponsePtr);

            return new NativePathResponse(Short.toUnsignedInt(status), secret);
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("NativePathHttpClient is closed!");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ForeignBridge.clientDestroy(clientHandle);
        }
    }
}
