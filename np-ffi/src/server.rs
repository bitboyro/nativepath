//! C ABI for the server engine. Each Java worker drives one lane with `np_lane_next`, which
//! returns everything about a request in one call, and answers with `np_server_respond`;
//! native code never calls into the JVM.

use super::{error_cstring, guard, PANIC_MSG};
use np_http_server::{
    NativePathServer, Limits, Next, ResponseWriter, ServerConfig, ServerRequest, TlsFiles,
};
use std::ffi::CStr;
use std::net::{IpAddr, SocketAddr};
use std::os::raw::c_char;
use std::time::Duration;

/// Server limits for `np_server_start`; a 0 field keeps that limit's default.
#[repr(C)]
pub struct NativePathServerLimits {
    pub header_read_timeout_ms: u64,
    pub idle_timeout_ms: u64,
    pub body_read_timeout_ms: u64,
    pub tls_handshake_timeout_ms: u64,
    pub max_header_bytes: u64,
    pub max_connections: u64,
    pub max_inflight_body_bytes: u64,
    // Lane tuning and the queue limit (`ServerConfig` fields of the same names; 0: default).
    pub batch: u64,
    pub spin: u64,
    pub work_stealing: u64,
    pub max_queued: u64,
}

impl NativePathServerLimits {
    fn to_limits(&self) -> Limits {
        let d = Limits::default();
        let ms = |v: u64, default: Duration| {
            if v == 0 {
                default
            } else {
                Duration::from_millis(v)
            }
        };
        let n = |v: u64, default: usize| if v == 0 { default } else { v as usize };
        Limits {
            header_read_timeout: ms(self.header_read_timeout_ms, d.header_read_timeout),
            idle_timeout: ms(self.idle_timeout_ms, d.idle_timeout),
            body_read_timeout: ms(self.body_read_timeout_ms, d.body_read_timeout),
            tls_handshake_timeout: ms(self.tls_handshake_timeout_ms, d.tls_handshake_timeout),
            max_header_bytes: n(self.max_header_bytes, d.max_header_bytes),
            max_connections: n(self.max_connections, d.max_connections),
            max_inflight_body_bytes: n(self.max_inflight_body_bytes, d.max_inflight_body_bytes),
        }
    }
}

pub struct NativePathServerHandle {
    inner: NativePathServer,
}

pub struct NativePathServerRequestHandle {
    inner: ServerRequest,
    /// The peer's IP address as text, borrowed by the request view.
    peer: String,
}

pub struct NativePathResponseWriterHandle {
    inner: ResponseWriter,
}

/// Everything about one request, filled by `np_lane_next` in a single call. `rc` is 1 with a
/// request, 0 on timeout, -1 once stopped. Method and target are UTF-8, borrowed from the request
/// handle. Headers (`name\0value\0` pairs) and body are handed over: the caller frees each with
/// `np_wipe_and_free(ptr, len, cap)` (null when empty). The peer address (IP as text, empty if
/// unknown) is borrowed from the request handle; ports are 0 when unknown.
#[repr(C)]
pub struct NativePathRequestView {
    pub rc: i32,
    pub method_ptr: *const u8,
    pub method_len: usize,
    pub target_ptr: *const u8,
    pub target_len: usize,
    pub headers_ptr: *mut u8,
    pub headers_len: usize,
    pub headers_cap: usize,
    pub body_ptr: *mut u8,
    pub body_len: usize,
    pub body_cap: usize,
    pub peer_ptr: *const u8,
    pub peer_len: usize,
    pub peer_port: u16,
    pub local_port: u16,
}

fn hand_over(buf: np_http_client::SecretBuffer) -> (*mut u8, usize, usize) {
    if buf.is_empty() {
        (std::ptr::null_mut(), 0, 0)
    } else {
        buf.into_raw_parts()
    }
}

/// Starts a server on `host:port` (port 0 picks a free one) with `lanes` event loops (0: one per
/// core). With `cert_path` and `key_path`
/// (PEM files; both or neither may be null) the engine terminates TLS itself. `limits` may be null
/// (all defaults). Returns null on
/// failure and sets `*out_error` (free with `np_string_free`). On success writes the bound port.
#[no_mangle]
pub unsafe extern "C" fn np_server_start(
    host: *const c_char,
    port: u16,
    max_body_bytes: usize,
    lanes: usize,
    cert_path: *const c_char,
    key_path: *const c_char,
    limits: *const NativePathServerLimits,
    out_port: *mut u16,
    out_error: *mut *mut c_char,
) -> *mut NativePathServerHandle {
    guard(
        || {
            if host.is_null() || out_port.is_null() || out_error.is_null() {
                if !out_error.is_null() {
                    *out_error = error_cstring("Invalid host or out parameter");
                }
                return std::ptr::null_mut();
            }
            let ip: IpAddr = match CStr::from_ptr(host)
                .to_str()
                .ok()
                .and_then(|h| h.parse().ok())
            {
                Some(ip) => ip,
                None => {
                    *out_error = error_cstring("host must be an IP address");
                    return std::ptr::null_mut();
                }
            };
            let path = |p: *const c_char| -> Option<std::path::PathBuf> {
                if p.is_null() {
                    None
                } else {
                    CStr::from_ptr(p)
                        .to_str()
                        .ok()
                        .map(std::path::PathBuf::from)
                }
            };
            let tls = match (path(cert_path), path(key_path)) {
                (Some(cert_chain), Some(private_key)) => Some(TlsFiles {
                    cert_chain,
                    private_key,
                }),
                (None, None) => None,
                _ => {
                    *out_error =
                        error_cstring("TLS needs both a certificate and a private key path");
                    return std::ptr::null_mut();
                }
            };
            let config = ServerConfig {
                addr: SocketAddr::new(ip, port),
                max_body_bytes,
                lanes: if lanes == 0 {
                    ServerConfig::default().lanes
                } else {
                    lanes
                },
                tls,
                limits: if limits.is_null() {
                    Limits::default()
                } else {
                    (*limits).to_limits()
                },
                batch: limits.as_ref().map_or(1, |l| (l.batch as usize).max(1)),
                spin: limits.as_ref().map_or(0, |l| l.spin as usize),
                work_stealing: limits.as_ref().is_some_and(|l| l.work_stealing != 0),
                max_queued: limits.as_ref().map_or(0, |l| l.max_queued as usize),
            };
            match NativePathServer::start(config) {
                Ok(server) => {
                    *out_port = server.local_addr().port();
                    Box::into_raw(Box::new(NativePathServerHandle { inner: server }))
                }
                Err(e) => {
                    *out_error = error_cstring(e);
                    std::ptr::null_mut()
                }
            }
        },
        || {
            if !out_error.is_null() {
                *out_error = error_cstring(PANIC_MSG);
            }
            std::ptr::null_mut()
        },
    )
}

/// Hands headers and body over and fills `out`; returns the request handle.
fn fill_view(
    mut r: ServerRequest,
    out: &mut NativePathRequestView,
) -> *mut NativePathServerRequestHandle {
    let (headers_ptr, headers_len, headers_cap) = hand_over(r.take_headers());
    let (body_ptr, body_len, body_cap) = hand_over(r.take_body());
    let peer = r.peer.map(|a| a.ip().to_string()).unwrap_or_default();
    let peer_port = r.peer.map(|a| a.port()).unwrap_or(0);
    let local_port = r.local.map(|a| a.port()).unwrap_or(0);
    let handle = Box::new(NativePathServerRequestHandle { inner: r, peer });
    *out = NativePathRequestView {
        rc: 1,
        method_ptr: handle.inner.method.as_ptr(),
        method_len: handle.inner.method.len(),
        target_ptr: handle.inner.target.as_ptr(),
        target_len: handle.inner.target.len(),
        headers_ptr,
        headers_len,
        headers_cap,
        body_ptr,
        body_len,
        body_cap,
        peer_ptr: handle.peer.as_ptr(),
        peer_len: handle.peer.len(),
        peer_port,
        local_port,
    };
    Box::into_raw(handle)
}

/// Engine timings summed since start: writes up to `n` of `[requests, read_ns, queue_ns,
/// handler_ns, respond_ns, reply_ns, shed_requests, shed_connections, stolen]` to `out` and returns
/// how many it wrote.
#[no_mangle]
pub unsafe extern "C" fn np_server_stats(
    server: *mut NativePathServerHandle,
    out: *mut u64,
    n: usize,
) -> usize {
    guard(
        || {
            let (Some(s), false) = (server.as_ref(), out.is_null()) else {
                return 0;
            };
            let snap = s.inner.stats().snapshot();
            let k = snap.len().min(n);
            std::ptr::copy_nonoverlapping(snap.as_ptr(), out, k);
            k
        },
        || 0,
    )
}

/// Engine phase histograms: writes up to `n` counts to `out` (5 phases in the order of
/// `np_server_stats` read, queue, handler, respond, reply; 160 log-scale buckets each, four per
/// power of two of nanoseconds, flattened) and returns how many it wrote.
#[no_mangle]
pub unsafe extern "C" fn np_server_phase_hist(
    server: *mut NativePathServerHandle,
    out: *mut u64,
    n: usize,
) -> usize {
    guard(
        || {
            let (Some(s), false) = (server.as_ref(), out.is_null()) else {
                return 0;
            };
            let snap = s.inner.stats().hist_snapshot();
            let k = snap.len().min(n);
            std::ptr::copy_nonoverlapping(snap.as_ptr(), out, k);
            k
        },
        || 0,
    )
}

/// Benchmark hook: a request that did not come from the network (typical browser headers plus
/// `extra_headers` more, and a body of `body_len` bytes), filled into `out` exactly as
/// `np_lane_next` would. Answer or free it as usual; the reply goes nowhere.
#[no_mangle]
pub unsafe extern "C" fn np_test_server_request(
    extra_headers: usize,
    body_len: usize,
    out: *mut NativePathRequestView,
) -> *mut NativePathServerRequestHandle {
    guard(
        || {
            let Some(out) = out.as_mut() else {
                return std::ptr::null_mut();
            };
            let mut packed = Vec::new();
            let mut add = |k: &str, v: &str| {
                packed.extend_from_slice(k.as_bytes());
                packed.push(0);
                packed.extend_from_slice(v.as_bytes());
                packed.push(0);
            };
            add("host", "127.0.0.1:8080");
            add(
                "user-agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
            );
            add("accept", "application/json, text/plain, */*");
            add("accept-encoding", "gzip, deflate, br");
            add("accept-language", "en-US,en;q=0.9");
            add("content-type", "application/json");
            let len = body_len.to_string();
            add("content-length", &len);
            for i in 0..extra_headers {
                add(&format!("x-extra-{i}"), "some-value-0123456789");
            }
            let method = if body_len > 0 { "POST" } else { "GET" };
            let r =
                ServerRequest::synthetic(method, "/api/orders?id=42", packed, vec![b'x'; body_len]);
            fill_view(r, out)
        },
        || std::ptr::null_mut(),
    )
}

/// Number of lanes; drive each with `np_lane_next` from exactly one thread.
#[no_mangle]
pub unsafe extern "C" fn np_server_lanes(server: *mut NativePathServerHandle) -> usize {
    guard(
        || server.as_ref().map(|s| s.inner.lanes().len()).unwrap_or(0),
        || 0,
    )
}

/// Runs lane `lane`'s event loop on the calling thread for up to `timeout_ms` and returns the next
/// request (null on timeout or stop; see `out.rc`). The request must be passed to
/// `np_server_respond` or `np_server_request_free` exactly once.
#[no_mangle]
pub unsafe extern "C" fn np_lane_next(
    server: *mut NativePathServerHandle,
    lane: usize,
    timeout_ms: u64,
    out: *mut NativePathRequestView,
) -> *mut NativePathServerRequestHandle {
    guard(
        || {
            let (Some(s), Some(out)) = (server.as_ref(), out.as_mut()) else {
                return std::ptr::null_mut();
            };
            let Some(l) = s.inner.lanes().get(lane) else {
                out.rc = -1;
                return std::ptr::null_mut();
            };
            match l.next_request(Duration::from_millis(timeout_ms)) {
                Next::Request(r) => fill_view(r, out),
                Next::Timeout => {
                    out.rc = 0;
                    std::ptr::null_mut()
                }
                Next::Stopped => {
                    out.rc = -1;
                    std::ptr::null_mut()
                }
            }
        },
        || {
            if let Some(out) = out.as_mut() {
                out.rc = -1;
            }
            std::ptr::null_mut()
        },
    )
}

/// Runs lane `lane`'s event loop on the calling thread without taking a request, until one of its
/// requests is answered or `timeout_ms` passes: for a worker whose requests are being answered on
/// other threads and that has no room for more. Returns 1, or 0 once the server has stopped.
#[no_mangle]
pub unsafe extern "C" fn np_lane_drive(
    server: *mut NativePathServerHandle,
    lane: usize,
    timeout_ms: u64,
) -> i32 {
    guard(
        || {
            let Some(l) = server.as_ref().and_then(|s| s.inner.lanes().get(lane)) else {
                return 0;
            };
            i32::from(l.drive(Duration::from_millis(timeout_ms)))
        },
        || 0,
    )
}

/// Sends the response and releases the request (always, even on failure). `headers` is
/// `name\0value\0` pairs. Both are copied before returning and the call never blocks, so callers
/// may pass memory the JVM moves (a critical downcall on heap arrays). Returns 0, or -1 if the
/// response was invalid (the client then gets 500).
#[no_mangle]
pub unsafe extern "C" fn np_server_respond(
    req: *mut NativePathServerRequestHandle,
    status: u16,
    headers: *const u8,
    headers_len: usize,
    body: *const u8,
    body_len: usize,
) -> i32 {
    guard(
        || {
            if req.is_null() {
                return -1;
            }
            let r = Box::from_raw(req);
            let headers = if headers.is_null() {
                &[][..]
            } else {
                std::slice::from_raw_parts(headers, headers_len)
            };
            let body = if body.is_null() {
                &[][..]
            } else {
                std::slice::from_raw_parts(body, body_len)
            };
            match r.inner.respond(status, headers, body) {
                Ok(()) => 0,
                Err(_) => -1,
            }
        },
        || -1,
    )
}

/// Sends status and headers and returns a writer for a streamed body (feed it with
/// `np_response_write`, end it with `np_response_finish`). `content_length` < 0 means
/// unknown (chunked). Releases the request always, like `np_server_respond`, and never blocks,
/// so it may be a critical downcall. Returns null if the response was invalid (the client gets 500).
#[no_mangle]
pub unsafe extern "C" fn np_server_respond_stream(
    req: *mut NativePathServerRequestHandle,
    status: u16,
    headers: *const u8,
    headers_len: usize,
    content_length: i64,
) -> *mut NativePathResponseWriterHandle {
    guard(
        || {
            if req.is_null() {
                return std::ptr::null_mut();
            }
            let r = Box::from_raw(req);
            let headers = if headers.is_null() {
                &[][..]
            } else {
                std::slice::from_raw_parts(headers, headers_len)
            };
            let length = u64::try_from(content_length).ok();
            match r.inner.respond_streaming(status, headers, length) {
                Ok(w) => Box::into_raw(Box::new(NativePathResponseWriterHandle { inner: w })),
                Err(_) => std::ptr::null_mut(),
            }
        },
        || std::ptr::null_mut(),
    )
}

/// Copies `len` bytes into a streamed response. Blocks while the connection's buffer is full, so
/// it must not be a critical downcall (pass native memory) and must not run on the thread driving
/// the request's lane. Returns 0, or -1 once the client is gone.
#[no_mangle]
pub unsafe extern "C" fn np_response_write(
    w: *mut NativePathResponseWriterHandle,
    data: *const u8,
    len: usize,
) -> i32 {
    guard(
        || {
            let Some(w) = w.as_ref() else {
                return -1;
            };
            if len == 0 {
                return 0;
            }
            if data.is_null() {
                return -1;
            }
            match w.inner.write(std::slice::from_raw_parts(data, len)) {
                Ok(()) => 0,
                Err(_) => -1,
            }
        },
        || -1,
    )
}

/// Ends a streamed response and frees the writer: normally with `ok` != 0, else by closing the
/// connection (the client sees a truncated response).
#[no_mangle]
pub unsafe extern "C" fn np_response_finish(w: *mut NativePathResponseWriterHandle, ok: i32) {
    guard(
        || {
            if !w.is_null() {
                let w = Box::from_raw(w);
                if ok != 0 {
                    w.inner.finish();
                } else {
                    w.inner.abort();
                }
            }
        },
        || (),
    )
}

/// Releases a request without answering it; the client gets 500.
#[no_mangle]
pub unsafe extern "C" fn np_server_request_free(req: *mut NativePathServerRequestHandle) {
    guard(
        || {
            if !req.is_null() {
                drop(Box::from_raw(req));
            }
        },
        || (),
    )
}

/// Stops accepting and closes connections; waiting `np_lane_next` calls return rc -1.
#[no_mangle]
pub unsafe extern "C" fn np_server_stop(server: *mut NativePathServerHandle) {
    guard(
        || {
            if let Some(s) = server.as_ref() {
                s.inner.stop();
            }
        },
        || (),
    )
}

/// Stops the server (if running) and frees it. No worker may still be inside `np_lane_next`.
#[no_mangle]
pub unsafe extern "C" fn np_server_destroy(server: *mut NativePathServerHandle) {
    guard(
        || {
            if !server.is_null() {
                drop(Box::from_raw(server));
            }
        },
        || (),
    )
}
