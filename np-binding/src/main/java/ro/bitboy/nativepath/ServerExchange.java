package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * One server request and its response. The request head and body live in native memory and are
 * wiped when the exchange is answered or closed.
 *
 * <p>Headers and {@link #body()} are native memory owned by this exchange. They are zeroed as soon
 * as the exchange is answered or closed, and freed only once nothing can reach them any more
 * (garbage collection), so a stale view can at worst read zeros, never freed memory or another
 * request's data. Header values become Java strings only when asked for, one header at a time
 * ({@link #header}, {@link #headerValues}); {@link #headers()} builds the whole map on first use.
 * Answer exactly once; closing an unanswered exchange sends 500.
 *
 * <p>An exchange may be used from several threads in turn (e.g. reactive pipelines), but not
 * concurrently.
 */
public final class ServerExchange implements AutoCloseable {

    private static final long METHOD_PTR = ForeignBridge.REQUEST_VIEW.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("method_ptr"));
    private static final long METHOD_LEN = METHOD_PTR + 8;
    private static final long TARGET_PTR = METHOD_PTR + 16;
    private static final long TARGET_LEN = METHOD_PTR + 24;
    private static final long HEADERS_PTR = METHOD_PTR + 32;
    private static final long HEADERS_LEN = METHOD_PTR + 40;
    private static final long HEADERS_CAP = METHOD_PTR + 48;
    private static final long BODY_PTR = METHOD_PTR + 56;
    private static final long BODY_LEN = METHOD_PTR + 64;
    private static final long BODY_CAP = METHOD_PTR + 72;
    private static final long PEER_PTR = ForeignBridge.REQUEST_VIEW.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("peer_ptr"));
    private static final long PEER_LEN = PEER_PTR + 8;
    private static final long PEER_PORT = PEER_PTR + 16;
    private static final long LOCAL_PORT = PEER_PTR + 18;

    private MemorySegment request;
    private final String method;
    private final String target;
    private final String peerAddress;
    private final int peerPort;
    private final int localPort;
    private final ServerLane lane;
    private final Set<String> secretHeaders;
    // name\0value\0 pairs, names lower case; owned by this exchange and never handed out, so it is
    // wiped and freed as soon as the exchange is answered or closed (see wipe()); empty after that.
    private MemorySegment headerBlock;
    private final long headerAddress;
    private final long headerCapacity;
    private final MemorySegment body;
    private Map<String, List<String>> headerMap;

    /** Reads the request view filled by the lane's poll (method, target and peer are copied now). */
    ServerExchange(MemorySegment request, MemorySegment view, Set<String> secretHeaders) {
        this(request, view, secretHeaders, null);
    }

    ServerExchange(MemorySegment request, MemorySegment view, Set<String> secretHeaders, ServerLane lane) {
        this.request = request;
        this.secretHeaders = secretHeaders;
        this.lane = lane;
        this.method = ascii(view.get(ValueLayout.ADDRESS, METHOD_PTR), view.get(ValueLayout.JAVA_LONG, METHOD_LEN));
        this.target = utf8(view.get(ValueLayout.ADDRESS, TARGET_PTR), view.get(ValueLayout.JAVA_LONG, TARGET_LEN));
        long peerLen = view.get(ValueLayout.JAVA_LONG, PEER_LEN);
        this.peerAddress = peerLen == 0 ? null : utf8(view.get(ValueLayout.ADDRESS, PEER_PTR), peerLen);
        this.peerPort = Short.toUnsignedInt(view.get(ValueLayout.JAVA_SHORT, PEER_PORT));
        this.localPort = Short.toUnsignedInt(view.get(ValueLayout.JAVA_SHORT, LOCAL_PORT));
        MemorySegment headers = view.get(ValueLayout.ADDRESS, HEADERS_PTR);
        long headersLen = view.get(ValueLayout.JAVA_LONG, HEADERS_LEN);
        this.headerAddress = headers.address();
        this.headerCapacity = view.get(ValueLayout.JAVA_LONG, HEADERS_CAP);
        this.headerBlock = headers.equals(MemorySegment.NULL) || headersLen == 0
                ? MemorySegment.NULL : headers.reinterpret(headersLen);
        this.body = owned(view.get(ValueLayout.ADDRESS, BODY_PTR), view.get(ValueLayout.JAVA_LONG, BODY_LEN),
                view.get(ValueLayout.JAVA_LONG, BODY_CAP));
    }

    private static String ascii(MemorySegment ptr, long len) {
        // The common methods without allocating.
        if (len == 3 && ptr.reinterpret(3).get(ValueLayout.JAVA_BYTE, 0) == 'G') return "GET";
        if (len == 4 && ptr.reinterpret(4).get(ValueLayout.JAVA_BYTE, 0) == 'P' && ptr.reinterpret(4).get(ValueLayout.JAVA_BYTE, 1) == 'O') return "POST";
        return new String(ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.US_ASCII);
    }

    private static String utf8(MemorySegment ptr, long len) {
        return len == 0 ? "" : new String(ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /**
     * Ties freeing to reachability (no per-request arena close, which stalls every JVM thread). For
     * what the application can hold on to (the body): a stale view then reads zeros, never freed
     * memory. Costs a Cleaner registration, so the header block, which never leaves this class, is
     * freed directly instead.
     */
    private static MemorySegment owned(MemorySegment ptr, long len, long cap) {
        if (ptr.equals(MemorySegment.NULL) || len == 0) {
            return MemorySegment.NULL;
        }
        long address = ptr.address();
        return ptr.reinterpret(len, Arena.ofAuto(), unused -> ForeignBridge.wipeAndFree(address, len, cap));
    }

    public String method() {
        return method;
    }

    /** Path and query as sent, e.g. {@code /api/users?id=7}. */
    public String target() {
        return target;
    }

    /** The client's IP address (e.g. {@code 127.0.0.1}), or {@code null} if unknown. */
    public String peerAddress() {
        return peerAddress;
    }

    /** The client's port, or 0 if unknown. */
    public int peerPort() {
        return peerPort;
    }

    /** The local port the request arrived on, or 0 if unknown. */
    public int localPort() {
        return localPort;
    }

    /** First value of a request header (case-insensitive), or {@code null}. Secret headers read as absent. */
    public String header(String name) {
        List<String> v = headerValues(name);
        return v == null ? null : v.get(0);
    }

    /** All values of a request header (case-insensitive), or {@code null}. Secret headers read as absent. */
    public List<String> headerValues(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (secretHeaders.contains(lower)) {
            return null;
        }
        byte[] want = lower.getBytes(StandardCharsets.ISO_8859_1);
        MemorySegment h = headerBlock;
        List<String> out = null;
        long n = h.byteSize(), i = 0;
        while (i < n) {
            long ne = indexOfNul(h, i, n);
            long ve = indexOfNul(h, ne + 1, n);
            if (ne - i == want.length && MemorySegment.mismatch(h, i, ne, MemorySegment.ofArray(want), 0, want.length) == -1) {
                if (out == null) out = new ArrayList<>(1);
                out.add(string(h, ne + 1, ve));
            }
            i = ve + 1;
        }
        return out;
    }

    /** Calls {@code action} for every non-secret header in arrival order (names lower case). */
    public void forEachHeader(BiConsumer<String, String> action) {
        MemorySegment h = headerBlock;
        long n = h.byteSize(), i = 0;
        while (i < n) {
            long ne = indexOfNul(h, i, n);
            long ve = indexOfNul(h, ne + 1, n);
            if (ne > i) {
                String name = string(h, i, ne);
                if (!secretHeaders.contains(name)) {
                    action.accept(name, string(h, ne + 1, ve));
                }
            }
            i = ve + 1;
        }
    }

    /**
     * All request headers (names lower case, in arrival order); values decoded as ISO-8859-1. Secret
     * headers (see {@link NativePathHttpServer.Builder#secretHeaders}) are left out entirely. Built on
     * first use; prefer {@link #header} when only a few are needed.
     */
    public Map<String, List<String>> headers() {
        if (headerMap == null) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            forEachHeader((k, v) -> out.computeIfAbsent(k, x -> new ArrayList<>(1)).add(v));
            headerMap = Collections.unmodifiableMap(out);
        }
        return headerMap;
    }

    /**
     * Copies the first value of a secret request header into a new {@link NativeSecret} (native to
     * native; never a Java string). Returns {@code null} if the header is absent. Only headers
     * configured as secret can be read this way. The caller closes the secret; call before answering.
     */
    public NativeSecret headerSecret(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!secretHeaders.contains(lower)) {
            throw new IllegalArgumentException("'" + name + "' is not configured as a secret header");
        }
        if (request == null) {
            throw new IllegalStateException("exchange already answered");
        }
        MemorySegment h = headerBlock;
        byte[] want = lower.getBytes(StandardCharsets.US_ASCII);
        long n = h.byteSize(), i = 0;
        while (i < n) {
            long ne = indexOfNul(h, i, n);
            long ve = indexOfNul(h, ne + 1, n);
            if (ne - i == want.length && MemorySegment.mismatch(h, i, ne, MemorySegment.ofArray(want), 0, want.length) == -1) {
                long len = ve - (ne + 1);
                NativeSecret s = NativeSecret.allocate(len, false);
                if (len > 0) {
                    MemorySegment.copy(h, ne + 1, s.asMemorySegment(), 0, len);
                }
                return s;
            }
            i = ve + 1;
        }
        return null;
    }

    /**
     * Copies the request body into a new {@link NativeSecret} (native to native) that refuses heap
     * copies. The caller closes it; the exchange's own body is still wiped when answered.
     */
    public NativeSecret bodySecret() {
        if (request == null) {
            throw new IllegalStateException("exchange already answered");
        }
        long len = body.equals(MemorySegment.NULL) ? 0 : body.byteSize();
        NativeSecret s = NativeSecret.allocate(len, false);
        if (len > 0) {
            MemorySegment.copy(body, 0, s.asMemorySegment(), 0, len);
        }
        return s;
    }

    /** The request body in native memory ({@code NULL} if none); zeroed once answered or closed. */
    public MemorySegment body() {
        return body;
    }

    public boolean isAnswered() {
        return request == null;
    }

    /** Sends the response. {@code Content-Length} and {@code Transfer-Encoding} are set by the server. */
    public void respond(int status, Map<String, List<String>> headers, byte[] body) {
        respond(status, headers, body.length == 0 ? MemorySegment.NULL : MemorySegment.ofArray(body));
    }

    /**
     * Sends the response. The body may be native memory or a heap segment (for example
     * {@code MemorySegment.ofArray} or {@code MemorySegment.ofBuffer} over a heap buffer): the engine
     * copies it once, straight from where it is.
     */
    public void respond(int status, Map<String, List<String>> headers, MemorySegment body) {
        if (request == null) {
            throw new IllegalStateException("exchange already answered");
        }
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("invalid status " + status);
        }
        byte[] packed = pack(headers);
        MemorySegment req = request;
        request = null;
        int rc;
        try {
            long bodyLen = body.equals(MemorySegment.NULL) ? 0 : body.byteSize();
            rc = ForeignBridge.serverRespond(req, (short) status,
                    packed.length == 0 ? MemorySegment.NULL : MemorySegment.ofArray(packed), packed.length, body, bodyLen);
        } finally {
            wipe();
        }
        if (rc != 0) {
            throw new IllegalArgumentException("response rejected (invalid header name or value); the client got 500");
        }
    }

    /**
     * Sends status and headers now and returns a stream for the body, for responses too large to
     * buffer or produced over time. With {@code contentLength} &ge; 0 the body must have exactly that
     * many bytes (else the connection is closed); with -1 it is chunked. The request's headers and
     * body stay readable until the stream is closed, then they are wiped.
     *
     * <p>Writes block while the client is slower than the producer, so they must not run on the
     * thread driving this exchange's lane (it would wait for itself; the stream refuses).
     */
    public ResponseStream respondStreaming(int status, Map<String, List<String>> headers, long contentLength) {
        if (request == null) {
            throw new IllegalStateException("exchange already answered");
        }
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("invalid status " + status);
        }
        byte[] packed = pack(headers);
        MemorySegment req = request;
        request = null;
        MemorySegment writer = ForeignBridge.serverRespondStream(req, (short) status,
                packed.length == 0 ? MemorySegment.NULL : MemorySegment.ofArray(packed), packed.length,
                contentLength < 0 ? -1 : contentLength);
        if (writer.equals(MemorySegment.NULL)) {
            wipe();
            throw new IllegalArgumentException("response rejected (invalid header name or value); the client got 500");
        }
        return new ResponseStream(writer, lane, this::wipe);
    }

    /** Releases the exchange; if it was never answered, the client gets 500. */
    @Override
    public void close() {
        if (request != null) {
            MemorySegment req = request;
            request = null;
            wipe();
            ForeignBridge.serverRequestFree(req);
        }
    }

    private void wipe() {
        if (!body.equals(MemorySegment.NULL)) {
            body.fill((byte) 0);
        }
        MemorySegment h = headerBlock;
        if (!h.equals(MemorySegment.NULL)) {
            headerBlock = MemorySegment.NULL; // later reads find no headers
            ForeignBridge.wipeAndFree(headerAddress, h.byteSize(), headerCapacity);
        }
    }

    private static long indexOfNul(MemorySegment seg, long from, long limit) {
        long i = from;
        while (i < limit && seg.get(ValueLayout.JAVA_BYTE, i) != 0) i++;
        return i;
    }

    private static String string(MemorySegment seg, long from, long to) {
        if (to <= from) return "";
        return new String(seg.asSlice(from, to - from).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.ISO_8859_1);
    }

    private static byte[] pack(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return new byte[0];
        }
        // Sized exactly first, then filled: no growing buffer and no second copy per answer.
        int size = 0;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            checkHeaderText(name);
            for (String v : e.getValue()) {
                checkHeaderText(v);
                size += name.length() + v.length() + 2;
            }
        }
        byte[] out = new byte[size];
        int at = 0;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            for (String v : e.getValue()) {
                at = latin1(name, out, at);
                out[at++] = 0;
                at = latin1(v, out, at);
                out[at++] = 0;
            }
        }
        return out;
    }

    /** ISO-8859-1 into {@code out} at {@code at} (characters outside it become '?', as getBytes does). */
    private static int latin1(String s, byte[] out, int at) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[at++] = c <= 0xFF ? (byte) c : (byte) '?';
        }
        return at;
    }

    /** NUL would break the packed format; CR/LF would allow response splitting. */
    private static void checkHeaderText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0 || c == '\r' || c == '\n') {
                throw new IllegalArgumentException("header contains NUL, CR or LF");
            }
        }
    }
}
