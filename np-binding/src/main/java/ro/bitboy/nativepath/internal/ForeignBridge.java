package ro.bitboy.nativepath.internal;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Low-level Project Panama FFM bridge to the native libnativepath library.
 */
public final class ForeignBridge {

    public static final StructLayout RESPONSE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("status"),
        MemoryLayout.paddingLayout(6),
        ValueLayout.ADDRESS.withName("body_ptr"),
        ValueLayout.JAVA_LONG.withName("body_len"),
        ValueLayout.JAVA_LONG.withName("body_cap"),
        ValueLayout.ADDRESS.withName("error_msg")
    );

    private static final MethodHandle CLIENT_CREATE;
    private static final MethodHandle CLIENT_DESTROY;
    private static final MethodHandle CLIENT_SET_INSECURE;
    private static final MethodHandle NATIVEPATH_GET;
    private static final MethodHandle RESPONSE_FREE;
    private static final MethodHandle WIPE_AND_FREE;
    private static final MethodHandle SET_MAX_BODY;
    private static final MethodHandle LAST_TIMINGS;
    private static final MethodHandle RESPONSE_SHELL_FREE;
    private static final MethodHandle TEST_NOOP;
    private static final MethodHandle TEST_PANIC;
    private static final MethodHandle TEST_RESPONSE;
    private static final MethodHandle STREAM_OPEN;
    private static final MethodHandle STREAM_NEXT;
    private static final MethodHandle STREAM_ERROR;
    private static final MethodHandle STREAM_FREE;
    private static final MethodHandle STRING_FREE;
    private static final MethodHandle SERVER_START;
    private static final MethodHandle SERVER_LANES;
    private static final MethodHandle LANE_NEXT;
    private static final MethodHandle LANE_DRIVE;
    private static final MethodHandle SERVER_RESPOND;
    private static final MethodHandle SERVER_REQUEST_FREE;
    private static final MethodHandle SERVER_RESPOND_STREAM;
    private static final MethodHandle RESPONSE_WRITE;
    private static final MethodHandle RESPONSE_FINISH;
    private static final MethodHandle SERVER_STOP;
    private static final MethodHandle SERVER_DESTROY;
    private static final MethodHandle SERVER_STATS;
    private static final MethodHandle SERVER_PHASE_HIST;
    private static final MethodHandle TEST_SERVER_REQUEST;
    private static final MethodHandle SET_ISOLATE_ALL;
    private static final MethodHandle STREAM_HEADERS;
    private static final MethodHandle REQUEST_METHOD;
    private static final MethodHandle REQUEST_BODY;
    private static final MethodHandle REQUEST_NEW;
    private static final MethodHandle REQUEST_HEADER;
    private static final MethodHandle REQUEST_FREE;
    private static final MethodHandle STREAM_OPEN_WITH;
    private static final MethodHandle SECRET_ALLOC;
    private static final MethodHandle SCAN_MEMORY;
    private static final MethodHandle SCAN_HITS;
    private static final MethodHandle KEYCHAIN_READ;
    private static final SymbolLookup LIBRARY;

    static {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = loadLibrary();
            LIBRARY = lookup;

            CLIENT_CREATE = linker.downcallHandle(
                lookup.find("np_client_create").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
            );

            CLIENT_DESTROY = linker.downcallHandle(
                lookup.find("np_client_destroy").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            CLIENT_SET_INSECURE = linker.downcallHandle(
                lookup.find("np_client_set_insecure").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
            );

            NATIVEPATH_GET = linker.downcallHandle(
                lookup.find("np_get").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            RESPONSE_FREE = linker.downcallHandle(
                lookup.find("np_response_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            WIPE_AND_FREE = linker.downcallHandle(
                lookup.find("np_wipe_and_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
            );

            SET_MAX_BODY = linker.downcallHandle(
                lookup.find("np_client_set_max_body").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );

            RESPONSE_SHELL_FREE = linker.downcallHandle(
                lookup.find("np_response_shell_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            LAST_TIMINGS = linker.downcallHandle(
                lookup.find("np_last_timings").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            TEST_NOOP = linker.downcallHandle(
                lookup.find("np_test_noop").orElseThrow(),
                FunctionDescriptor.ofVoid()
            );

            TEST_PANIC = linker.downcallHandle(
                lookup.find("np_test_panic").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT)
            );

            TEST_RESPONSE = linker.downcallHandle(
                lookup.find("np_test_response").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );

            SET_ISOLATE_ALL = linker.downcallHandle(
                lookup.find("np_client_set_isolate_all").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
            );

            STREAM_HEADERS = linker.downcallHandle(
                lookup.find("np_stream_headers").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            REQUEST_METHOD = linker.downcallHandle(
                lookup.find("np_request_method").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            REQUEST_BODY = linker.downcallHandle(
                lookup.find("np_request_body").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_BOOLEAN)
            );

            REQUEST_NEW = linker.downcallHandle(
                lookup.find("np_request_new").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS)
            );

            REQUEST_HEADER = linker.downcallHandle(
                lookup.find("np_request_header").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_BOOLEAN)
            );

            REQUEST_FREE = linker.downcallHandle(
                lookup.find("np_request_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            STREAM_OPEN_WITH = linker.downcallHandle(
                lookup.find("np_stream_open_with").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            SECRET_ALLOC = linker.downcallHandle(
                lookup.find("np_secret_alloc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            );

            SCAN_MEMORY = linker.downcallHandle(
                lookup.find("np_test_scan_memory").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_BYTE)
            );

            SCAN_HITS = linker.downcallHandle(
                lookup.find("np_test_scan_hits").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );

            KEYCHAIN_READ = linker.downcallHandle(
                lookup.find("np_keychain_read").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS)
            );

            STREAM_OPEN = linker.downcallHandle(
                lookup.find("np_stream_open").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            STREAM_NEXT = linker.downcallHandle(
                lookup.find("np_stream_next").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS)
            );

            STREAM_ERROR = linker.downcallHandle(
                lookup.find("np_stream_error").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            STREAM_FREE = linker.downcallHandle(
                lookup.find("np_stream_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            STRING_FREE = linker.downcallHandle(
                lookup.find("np_string_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            SERVER_START = linker.downcallHandle(
                lookup.find("np_server_start").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );






            SERVER_LANES = linker.downcallHandle(
                lookup.find("np_server_lanes").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            );

            LANE_NEXT = linker.downcallHandle(
                lookup.find("np_lane_next").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            );

            LANE_DRIVE = linker.downcallHandle(
                lookup.find("np_lane_drive").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG)
            );

            // Critical: copies its inputs and never blocks, so it may read heap arrays directly
            // (Spring's response bytes reach the engine with one copy, not two).
            SERVER_RESPOND = linker.downcallHandle(
                lookup.find("np_server_respond").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                Linker.Option.critical(true)
            );

            // Critical like SERVER_RESPOND: copies the headers and never blocks.
            SERVER_RESPOND_STREAM = linker.downcallHandle(
                lookup.find("np_server_respond_stream").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                Linker.Option.critical(true)
            );

            // Not critical: blocks while the connection's buffer is full (native memory only).
            RESPONSE_WRITE = linker.downcallHandle(
                lookup.find("np_response_write").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );

            RESPONSE_FINISH = linker.downcallHandle(
                lookup.find("np_response_finish").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            );

            SERVER_REQUEST_FREE = linker.downcallHandle(
                lookup.find("np_server_request_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            SERVER_STOP = linker.downcallHandle(
                lookup.find("np_server_stop").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            SERVER_DESTROY = linker.downcallHandle(
                lookup.find("np_server_destroy").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            SERVER_STATS = linker.downcallHandle(
                lookup.find("np_server_stats").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );

            SERVER_PHASE_HIST = linker.downcallHandle(
                lookup.find("np_server_phase_hist").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            );

            TEST_SERVER_REQUEST = linker.downcallHandle(
                lookup.find("np_test_server_request").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            );

        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    /** The loaded native library, for other modules (such as np-db) that bind their own functions. */
    public static SymbolLookup library() {
        return LIBRARY;
    }

    /** System property (or {@code NATIVEPATH_LIB} environment variable) naming the native library. */
    public static final String LIB_PROPERTY = "nativepath.lib";

    private static SymbolLookup loadLibrary() {
        // An explicit path wins and must exist; silently falling back would hide a wrong setup.
        String explicit = System.getProperty(LIB_PROPERTY, System.getenv("NATIVEPATH_LIB"));
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (!Files.exists(p)) {
                throw new IllegalStateException(LIB_PROPERTY + " points to a missing file: " + p.toAbsolutePath());
            }
            return SymbolLookup.libraryLookup(p.toAbsolutePath(), Arena.global());
        }

        // Release before debug, so a stale debug build never silently wins.
        String userDir = System.getProperty("user.dir", ".");
        Path[] searchPaths = new Path[] {
            Path.of(userDir, "target/release/libnativepath.dylib"),
            Path.of(userDir, "../target/release/libnativepath.dylib"),
            Path.of(userDir, "target/release/libnativepath.so"),
            Path.of(userDir, "../target/release/libnativepath.so"),
            Path.of(userDir, "target/debug/libnativepath.dylib"),
            Path.of(userDir, "../target/debug/libnativepath.dylib"),
            Path.of(userDir, "target/debug/libnativepath.so"),
            Path.of(userDir, "../target/debug/libnativepath.so")
        };

        for (Path p : searchPaths) {
            if (Files.exists(p)) {
                return SymbolLookup.libraryLookup(p.toAbsolutePath(), Arena.global());
            }
        }

        // Fallback to standard library loader
        return SymbolLookup.loaderLookup();
    }

    public static MemorySegment clientCreate() {
        try {
            return (MemorySegment) CLIENT_CREATE.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_client_create", t);
        }
    }

    public static void clientDestroy(MemorySegment handle) {
        try {
            CLIENT_DESTROY.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_client_destroy", t);
        }
    }

    public static void clientSetInsecure(MemorySegment handle, boolean insecure) {
        try {
            CLIENT_SET_INSECURE.invokeExact(handle, insecure);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_client_set_insecure", t);
        }
    }

    public static MemorySegment nativePathGet(MemorySegment handle, MemorySegment urlString) {
        try {
            return (MemorySegment) NATIVEPATH_GET.invokeExact(handle, urlString);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_get", t);
        }
    }

    public static void responseFree(MemorySegment responsePtr) {
        try {
            RESPONSE_FREE.invokeExact(responsePtr);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_response_free", t);
        }
    }

    public static void wipeAndFree(long address, long length, long capacity) {
        try {
            MemorySegment ptr = MemorySegment.ofAddress(address);
            WIPE_AND_FREE.invokeExact(ptr, length, capacity);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_wipe_and_free", t);
        }
    }

    public static void clientSetMaxBody(MemorySegment handle, long maxBytes) {
        try {
            SET_MAX_BODY.invokeExact(handle, maxBytes);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_client_set_max_body", t);
        }
    }

    public static MemorySegment streamOpen(MemorySegment client, MemorySegment url, MemorySegment outStatus,
                                           MemorySegment outContentLength, MemorySegment outError) {
        try {
            return (MemorySegment) STREAM_OPEN.invokeExact(client, url, outStatus, outContentLength, outError);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_stream_open", t);
        }
    }

    public static int streamNext(MemorySegment stream, MemorySegment outPtr, MemorySegment outLen) {
        try {
            return (int) STREAM_NEXT.invokeExact(stream, outPtr, outLen);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_stream_next", t);
        }
    }

    public static MemorySegment streamError(MemorySegment stream) {
        try {
            return (MemorySegment) STREAM_ERROR.invokeExact(stream);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_stream_error", t);
        }
    }

    public static void streamFree(MemorySegment stream) {
        try {
            STREAM_FREE.invokeExact(stream);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_stream_free", t);
        }
    }

    public static void stringFree(MemorySegment s) {
        try {
            STRING_FREE.invokeExact(s);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_string_free", t);
        }
    }

    /**
     * Rust-side timings of the last get / response-free on the calling thread, written as five
     * longs: headers_ns, body_ns, assemble_ns, wipe_free_ns, reserved.
     */
    public static void lastTimings(MemorySegment out40Bytes) {
        try {
            LAST_TIMINGS.invokeExact(out40Bytes);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_last_timings", t);
        }
    }

    /** Frees only the response struct; its body must already be owned by a {@code NativeSecret}. */
    public static void responseShellFree(MemorySegment responsePtr) {
        try {
            RESPONSE_SHELL_FREE.invokeExact(responsePtr);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_response_shell_free", t);
        }
    }

    /** Benchmark hook: an empty native call. */
    public static void testNoop() {
        try {
            TEST_NOOP.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_test_noop", t);
        }
    }

    /** Test hook: panics inside native code; returns -1 when the panic was contained. */
    public static int testPanic() {
        try {
            return (int) TEST_PANIC.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_test_panic", t);
        }
    }

    /** Benchmark hook: a response with a synthetic body of {@code len} bytes and no network. */
    public static MemorySegment testResponse(long len) {
        try {
            return (MemorySegment) TEST_RESPONSE.invokeExact(len);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_test_response", t);
        }
    }

    public static void clientSetIsolateAll(MemorySegment handle, boolean enabled) {
        try {
            SET_ISOLATE_ALL.invokeExact(handle, enabled);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_client_set_isolate_all", t);
        }
    }

    /** Response headers as {@code name\0value\0} pairs, borrowed from the stream. */
    public static MemorySegment streamHeaders(MemorySegment stream, MemorySegment outLen) {
        try {
            return (MemorySegment) STREAM_HEADERS.invokeExact(stream, outLen);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_stream_headers", t);
        }
    }

    public static int requestMethod(MemorySegment req, MemorySegment method) {
        try {
            return (int) REQUEST_METHOD.invokeExact(req, method);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_request_method", t);
        }
    }

    public static int requestBody(MemorySegment req, MemorySegment body, long len, boolean sensitive) {
        try {
            return (int) REQUEST_BODY.invokeExact(req, body, len, sensitive);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_request_body", t);
        }
    }

    public static MemorySegment requestNew() {
        try {
            return (MemorySegment) REQUEST_NEW.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_request_new", t);
        }
    }

    public static int requestHeader(MemorySegment req, MemorySegment name, MemorySegment prefix, long prefixLen,
                                    MemorySegment value, long valueLen, boolean sensitive) {
        try {
            return (int) REQUEST_HEADER.invokeExact(req, name, prefix, prefixLen, value, valueLen, sensitive);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_request_header", t);
        }
    }

    public static void requestFree(MemorySegment req) {
        try {
            REQUEST_FREE.invokeExact(req);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_request_free", t);
        }
    }

    public static MemorySegment streamOpenWith(MemorySegment client, MemorySegment url, MemorySegment req,
                                               MemorySegment outStatus, MemorySegment outContentLength,
                                               MemorySegment outError) {
        try {
            return (MemorySegment) STREAM_OPEN_WITH.invokeExact(client, url, req, outStatus, outContentLength, outError);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_stream_open_with", t);
        }
    }

    /** Allocates a zeroed native buffer; the capacity is written to {@code outCap}. */
    public static MemorySegment secretAlloc(long len, MemorySegment outCap) {
        try {
            return (MemorySegment) SECRET_ALLOC.invokeExact(len, outCap);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_secret_alloc", t);
        }
    }

    /** Test hook: counts occurrences of an XOR-masked value in this process's memory (-1 if unsupported). */
    public static long scanMemory(MemorySegment masked, long len, byte mask) {
        try {
            return (long) SCAN_MEMORY.invokeExact(masked, len, mask);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_test_scan_memory", t);
        }
    }

    public static long scanHits(MemorySegment out, long n) {
        try {
            return (long) SCAN_HITS.invokeExact(out, n);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_test_scan_hits", t);
        }
    }

    public static MemorySegment serverStart(MemorySegment host, short port, long maxBodyBytes, long lanes,
                                            MemorySegment certPath, MemorySegment keyPath, MemorySegment limits,
                                            MemorySegment outPort, MemorySegment outError) {
        try {
            return (MemorySegment) SERVER_START.invokeExact(host, port, maxBodyBytes, lanes, certPath, keyPath, limits, outPort, outError);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_start", t);
        }
    }






    /** Layout of {@code NativePathRequestView}, filled by {@link #laneNext}. */
    public static final StructLayout REQUEST_VIEW = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("rc"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("method_ptr"),
        ValueLayout.JAVA_LONG.withName("method_len"),
        ValueLayout.ADDRESS.withName("target_ptr"),
        ValueLayout.JAVA_LONG.withName("target_len"),
        ValueLayout.ADDRESS.withName("headers_ptr"),
        ValueLayout.JAVA_LONG.withName("headers_len"),
        ValueLayout.JAVA_LONG.withName("headers_cap"),
        ValueLayout.ADDRESS.withName("body_ptr"),
        ValueLayout.JAVA_LONG.withName("body_len"),
        ValueLayout.JAVA_LONG.withName("body_cap"),
        ValueLayout.ADDRESS.withName("peer_ptr"),
        ValueLayout.JAVA_LONG.withName("peer_len"),
        ValueLayout.JAVA_SHORT.withName("peer_port"),
        ValueLayout.JAVA_SHORT.withName("local_port"),
        MemoryLayout.paddingLayout(4)
    );

    public static long serverLanes(MemorySegment server) {
        try {
            return (long) SERVER_LANES.invokeExact(server);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_lanes", t);
        }
    }

    /** Drives one lane until a request is ready; fills {@code view} ({@link #REQUEST_VIEW}). */
    public static MemorySegment laneNext(MemorySegment server, long lane, long timeoutMillis, MemorySegment view) {
        try {
            return (MemorySegment) LANE_NEXT.invokeExact(server, lane, timeoutMillis, view);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_lane_next", t);
        }
    }

    /** Drives one lane without taking a request until one of its requests is answered; 0 once stopped. */
    public static int laneDrive(MemorySegment server, long lane, long timeoutMillis) {
        try {
            return (int) LANE_DRIVE.invokeExact(server, lane, timeoutMillis);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_lane_drive", t);
        }
    }

    public static int serverRespond(MemorySegment request, short status, MemorySegment headers, long headersLen,
                                    MemorySegment body, long bodyLen) {
        try {
            return (int) SERVER_RESPOND.invokeExact(request, status, headers, headersLen, body, bodyLen);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_respond", t);
        }
    }

    /** Sends status and headers of a streamed response; returns the writer, or NULL if invalid. */
    public static MemorySegment serverRespondStream(MemorySegment request, short status, MemorySegment headers,
                                                    long headersLen, long contentLength) {
        try {
            return (MemorySegment) SERVER_RESPOND_STREAM.invokeExact(request, status, headers, headersLen, contentLength);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_respond_stream", t);
        }
    }

    /** Writes native {@code data} to a streamed response; blocks while the connection is backed up. */
    public static int responseWrite(MemorySegment writer, MemorySegment data, long len) {
        try {
            return (int) RESPONSE_WRITE.invokeExact(writer, data, len);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_response_write", t);
        }
    }

    public static void responseFinish(MemorySegment writer, int ok) {
        try {
            RESPONSE_FINISH.invokeExact(writer, ok);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_response_finish", t);
        }
    }

    public static void serverRequestFree(MemorySegment request) {
        try {
            SERVER_REQUEST_FREE.invokeExact(request);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_request_free", t);
        }
    }

    public static void serverStop(MemorySegment server) {
        try {
            SERVER_STOP.invokeExact(server);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_stop", t);
        }
    }

    /** Writes up to {@code n} engine counters to {@code out}; see {@code NativePathHttpServer.engineStats()}. */
    public static long serverStats(MemorySegment server, MemorySegment out, long n) {
        try {
            return (long) SERVER_STATS.invokeExact(server, out, n);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_stats", t);
        }
    }

    /** Writes up to {@code n} phase-histogram counts to {@code out}; see {@code NativePathHttpServer.engineHistograms()}. */
    public static long serverPhaseHist(MemorySegment server, MemorySegment out, long n) {
        try {
            return (long) SERVER_PHASE_HIST.invokeExact(server, out, n);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_phase_hist", t);
        }
    }

    /** Benchmark hook: a synthetic request filled into {@code view} as a lane poll would. */
    public static MemorySegment testServerRequest(long extraHeaders, long bodyLen, MemorySegment view) {
        try {
            return (MemorySegment) TEST_SERVER_REQUEST.invokeExact(extraHeaders, bodyLen, view);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_test_server_request", t);
        }
    }

    public static void serverDestroy(MemorySegment server) {
        try {
            SERVER_DESTROY.invokeExact(server);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_server_destroy", t);
        }
    }

    /**
     * Reads a macOS keychain generic password into a wiped native buffer. Returns NULL on failure
     * with the {@code OSStatus} in {@code outStatus} ({@link Integer#MIN_VALUE} off macOS).
     */
    public static MemorySegment keychainRead(MemorySegment service, long serviceLen, MemorySegment account,
                                             long accountLen, MemorySegment outLen, MemorySegment outCap,
                                             MemorySegment outStatus) {
        try {
            return (MemorySegment) KEYCHAIN_READ.invokeExact(service, serviceLen, account, accountLen, outLen, outCap, outStatus);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke np_keychain_read", t);
        }
    }

    private ForeignBridge() {}
}
