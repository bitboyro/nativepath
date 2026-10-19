use np_http_client::alloc::ZeroizingAllocator;
use np_http_client::{
    ClientConfig, NativePathClient, NativePathStream, RequestHeader, SecretBuffer, Timings,
};
use std::cell::Cell;

mod server;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

mod db;

thread_local! {
    /// Rust-side timings of the most recent `np_get` / `np_response_free` on this thread.
    static LAST: Cell<[u64; 5]> = const { Cell::new([0; 5]) };
}

// Every Rust allocation in this library (including hyper/rustls/reqwest internals) is
// zeroed on free, so response plaintext cannot linger in freed memory.
#[global_allocator]
static GLOBAL: ZeroizingAllocator<std::alloc::System> = ZeroizingAllocator(std::alloc::System);

/// Runs an FFI entry point, turning a Rust panic into `on_panic()` (the function's normal error
/// result). A panic must never unwind into the JVM: Rust aborts the whole process when one crosses
/// an `extern "C"` boundary.
fn guard<T>(f: impl FnOnce() -> T, on_panic: impl FnOnce() -> T) -> T {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|_| on_panic())
}

const PANIC_MSG: &str = "internal error: panic in native code";

pub struct NativePathClientHandle {
    inner: NativePathClient,
}

#[repr(C)]
pub struct NativePathResponseRaw {
    pub status: u16,
    pub body_ptr: *mut u8,
    pub body_len: usize,
    pub body_cap: usize,
    pub error_msg: *const c_char,
}

fn error_response(msg: &str) -> *mut NativePathResponseRaw {
    Box::into_raw(Box::new(NativePathResponseRaw {
        status: 0,
        body_ptr: std::ptr::null_mut(),
        body_len: 0,
        body_cap: 0,
        error_msg: CString::new(msg).unwrap_or_default().into_raw(),
    }))
}

/// Creates a new NativePath client instance.
#[no_mangle]
pub extern "C" fn np_client_create() -> *mut NativePathClientHandle {
    guard(
        || match NativePathClient::new(ClientConfig::default()) {
            Ok(client) => Box::into_raw(Box::new(NativePathClientHandle { inner: client })),
            Err(_) => std::ptr::null_mut(),
        },
        || std::ptr::null_mut(),
    )
}

/// Destroys the client instance and releases its runtime resources.
#[no_mangle]
pub unsafe extern "C" fn np_client_destroy(handle: *mut NativePathClientHandle) {
    guard(
        || {
            if !handle.is_null() {
                drop(Box::from_raw(handle));
            }
        },
        || (),
    )
}

/// Dynamically enables or disables accepting self-signed / invalid certificates at runtime.
#[no_mangle]
pub unsafe extern "C" fn np_client_set_insecure(
    handle: *mut NativePathClientHandle,
    insecure: bool,
) {
    guard(
        || {
            if let Some(h) = handle.as_ref() {
                h.inner.set_trust_self_signed(insecure);
            }
        },
        || (),
    )
}

/// Executes an HTTP GET request. The response body is placed directly in off-heap memory.
#[no_mangle]
pub unsafe extern "C" fn np_get(
    handle: *mut NativePathClientHandle,
    url: *const c_char,
) -> *mut NativePathResponseRaw {
    guard(
        || {
            if handle.is_null() || url.is_null() {
                return Box::into_raw(Box::new(NativePathResponseRaw {
                    status: 0,
                    body_ptr: std::ptr::null_mut(),
                    body_len: 0,
                    body_cap: 0,
                    error_msg: CString::new("Invalid handle or url").unwrap().into_raw(),
                }));
            }

            let url_str = match CStr::from_ptr(url).to_str() {
                Ok(s) => s,
                Err(_) => {
                    return Box::into_raw(Box::new(NativePathResponseRaw {
                        status: 0,
                        body_ptr: std::ptr::null_mut(),
                        body_len: 0,
                        body_cap: 0,
                        error_msg: CString::new("URL is not valid UTF-8").unwrap().into_raw(),
                    }));
                }
            };

            let client = &(*handle).inner;
            match client.get(url_str) {
                Ok(resp) => {
                    let Timings {
                        headers_ns,
                        body_ns,
                        assemble_ns,
                    } = resp.timings;
                    LAST.with(|l| {
                        let mut v = l.get();
                        v[0] = headers_ns;
                        v[1] = body_ns;
                        v[2] = assemble_ns;
                        l.set(v);
                    });
                    let (body_ptr, body_len, body_cap) = resp.body.into_raw_parts();
                    Box::into_raw(Box::new(NativePathResponseRaw {
                        status: resp.status,
                        body_ptr,
                        body_len,
                        body_cap,
                        error_msg: std::ptr::null(),
                    }))
                }
                Err(e) => {
                    let err_c = CString::new(e.to_string()).unwrap_or_default().into_raw();
                    Box::into_raw(Box::new(NativePathResponseRaw {
                        status: 0,
                        body_ptr: std::ptr::null_mut(),
                        body_len: 0,
                        body_cap: 0,
                        error_msg: err_c,
                    }))
                }
            }
        },
        || error_response(PANIC_MSG),
    )
}

/// Volatile-wipes the response body in memory and frees all allocated native memory.
#[no_mangle]
pub unsafe extern "C" fn np_response_free(res: *mut NativePathResponseRaw) {
    guard(
        || {
            if !res.is_null() {
                let response = Box::from_raw(res);
                if !response.body_ptr.is_null() && response.body_cap > 0 {
                    let t = std::time::Instant::now();
                    SecretBuffer::free_raw(response.body_ptr, response.body_len, response.body_cap);
                    LAST.with(|l| {
                        let mut v = l.get();
                        v[3] = t.elapsed().as_nanos() as u64;
                        l.set(v);
                    });
                }
                if !response.error_msg.is_null() {
                    drop(CString::from_raw(response.error_msg as *mut c_char));
                }
            }
        },
        || (),
    )
}

/// Frees only the response struct, not its body or error message. Call this once the caller has
/// taken ownership of the body (as `NativeSecret` does); `np_response_free` is for the rest.
#[no_mangle]
pub unsafe extern "C" fn np_response_shell_free(res: *mut NativePathResponseRaw) {
    guard(
        || {
            if !res.is_null() {
                drop(Box::from_raw(res));
            }
        },
        || (),
    )
}

/// Explicitly volatile-wipes an arbitrary off-heap byte buffer and frees it.
#[no_mangle]
pub unsafe extern "C" fn np_wipe_and_free(ptr: *mut u8, len: usize, cap: usize) {
    guard(
        || {
            if !ptr.is_null() && cap > 0 {
                let t = std::time::Instant::now();
                SecretBuffer::free_raw(ptr, len, cap);
                LAST.with(|l| {
                    let mut v = l.get();
                    v[3] = t.elapsed().as_nanos() as u64;
                    l.set(v);
                });
            }
        },
        || (),
    )
}

/// Sets the largest body that buffered `np_get` accepts (bytes). Streams are unbounded.
#[no_mangle]
pub unsafe extern "C" fn np_client_set_max_body(
    handle: *mut NativePathClientHandle,
    max_bytes: usize,
) {
    guard(
        || {
            if let Some(h) = handle.as_ref() {
                h.inner.set_max_body_bytes(max_bytes);
            }
        },
        || (),
    )
}

pub struct NativePathStreamHandle {
    inner: NativePathStream,
    error: Option<CString>,
}

fn error_cstring(e: impl ToString) -> *mut c_char {
    CString::new(e.to_string().replace('\0', " "))
        .unwrap_or_default()
        .into_raw()
}

/// Starts a streaming GET. Returns null on failure and sets `*out_error` (free it with
/// `np_string_free`). On success fills `*out_status` and `*out_content_length`
/// (-1 when the server did not declare a length).
#[no_mangle]
pub unsafe extern "C" fn np_stream_open(
    handle: *mut NativePathClientHandle,
    url: *const c_char,
    out_status: *mut u16,
    out_content_length: *mut i64,
    out_error: *mut *mut c_char,
) -> *mut NativePathStreamHandle {
    guard(
        || {
            if handle.is_null()
                || url.is_null()
                || out_status.is_null()
                || out_content_length.is_null()
                || out_error.is_null()
            {
                if !out_error.is_null() {
                    *out_error = error_cstring("Invalid handle, url or out parameter");
                }
                return std::ptr::null_mut();
            }
            let url_str = match CStr::from_ptr(url).to_str() {
                Ok(s) => s,
                Err(_) => {
                    *out_error = error_cstring("URL is not valid UTF-8");
                    return std::ptr::null_mut();
                }
            };
            match (*handle).inner.get_stream(url_str) {
                Ok(stream) => {
                    *out_status = stream.status;
                    *out_content_length = stream.content_length.map(|n| n as i64).unwrap_or(-1);
                    Box::into_raw(Box::new(NativePathStreamHandle {
                        inner: stream,
                        error: None,
                    }))
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

/// Advances the stream. Returns 1 with `*out_ptr`/`*out_len` set to the next chunk (valid until
/// the next call or `np_stream_free`), 0 at end of body, or -1 on error
/// (see `np_stream_error`).
#[no_mangle]
pub unsafe extern "C" fn np_stream_next(
    handle: *mut NativePathStreamHandle,
    out_ptr: *mut *const u8,
    out_len: *mut usize,
) -> i32 {
    guard(
        || {
            let Some(h) = handle.as_mut() else { return -1 };
            match h.inner.next_chunk() {
                Ok(Some(chunk)) => {
                    *out_ptr = chunk.as_ptr();
                    *out_len = chunk.len();
                    1
                }
                Ok(None) => {
                    *out_ptr = std::ptr::null();
                    *out_len = 0;
                    0
                }
                Err(e) => {
                    h.error = CString::new(e.to_string().replace('\0', " ")).ok();
                    -1
                }
            }
        },
        || {
            if let Some(h) = handle.as_mut() {
                h.error = CString::new(PANIC_MSG).ok();
            }
            -1
        },
    )
}

/// Message for the last failed `np_stream_next`; owned by the handle.
#[no_mangle]
pub unsafe extern "C" fn np_stream_error(handle: *mut NativePathStreamHandle) -> *const c_char {
    guard(
        || match handle.as_ref().and_then(|h| h.error.as_ref()) {
            Some(e) => e.as_ptr(),
            None => std::ptr::null(),
        },
        || std::ptr::null(),
    )
}

/// Closes the stream and drops (and zeroes) any buffered data.
#[no_mangle]
pub unsafe extern "C" fn np_stream_free(handle: *mut NativePathStreamHandle) {
    guard(
        || {
            if !handle.is_null() {
                drop(Box::from_raw(handle));
            }
        },
        || (),
    )
}

/// Frees a string returned through an `out_error` parameter.
#[no_mangle]
pub unsafe extern "C" fn np_string_free(s: *mut c_char) {
    guard(
        || {
            if !s.is_null() {
                drop(CString::from_raw(s));
            }
        },
        || (),
    )
}

/// Writes the Rust-side timings of the last `np_get` and `np_response_free` made on
/// the calling thread: `out[0..5] = {headers_ns, body_ns, assemble_ns, wipe_free_ns, 0}`.
#[no_mangle]
pub unsafe extern "C" fn np_last_timings(out: *mut u64) {
    guard(
        || {
            if !out.is_null() {
                LAST.with(|l| std::ptr::copy_nonoverlapping(l.get().as_ptr(), out, 5));
            }
        },
        || (),
    )
}

/// Test hook: does nothing. Measures the bare cost of a native call from Java.
#[no_mangle]
pub extern "C" fn np_test_noop() {
    guard(|| {}, || ())
}

/// Test hook: builds a response exactly like `np_get` would, but with a synthetic body of
/// `len` bytes and no network. Free it with `np_response_free`.
#[no_mangle]
pub extern "C" fn np_test_response(len: usize) -> *mut NativePathResponseRaw {
    guard(
        || {
            let (body_ptr, body_len, body_cap) =
                SecretBuffer::new(vec![0x41; len]).into_raw_parts();
            Box::into_raw(Box::new(NativePathResponseRaw {
                status: 200,
                body_ptr,
                body_len,
                body_cap,
                error_msg: std::ptr::null(),
            }))
        },
        || std::ptr::null_mut(),
    )
}

/// A request under construction: extra headers, with values held in wiped native buffers.
pub struct NativePathRequestHandle {
    headers: Vec<RequestHeader>,
    method: String,
    body: Option<SecretBuffer>,
    sensitive_body: bool,
}

#[no_mangle]
pub extern "C" fn np_request_new() -> *mut NativePathRequestHandle {
    guard(
        || {
            Box::into_raw(Box::new(NativePathRequestHandle {
                headers: Vec::new(),
                method: "GET".to_string(),
                body: None,
                sensitive_body: false,
            }))
        },
        || std::ptr::null_mut(),
    )
}

/// Adds a header whose value is `prefix` (plain text, e.g. "Bearer ") followed by `value`
/// (copied from caller-owned native memory). Returns 0 on success, -1 on invalid input.
#[no_mangle]
pub unsafe extern "C" fn np_request_header(
    req: *mut NativePathRequestHandle,
    name: *const c_char,
    prefix_ptr: *const u8,
    prefix_len: usize,
    value_ptr: *const u8,
    value_len: usize,
    sensitive: bool,
) -> i32 {
    guard(
        || {
            let (Some(r), false) = (req.as_mut(), name.is_null()) else {
                return -1;
            };
            let Ok(name) = CStr::from_ptr(name).to_str() else {
                return -1;
            };
            let mut bytes = Vec::with_capacity(prefix_len + value_len);
            if !prefix_ptr.is_null() {
                bytes.extend_from_slice(std::slice::from_raw_parts(prefix_ptr, prefix_len));
            }
            if !value_ptr.is_null() {
                bytes.extend_from_slice(std::slice::from_raw_parts(value_ptr, value_len));
            }
            r.headers.push(RequestHeader {
                name: name.to_string(),
                value: SecretBuffer::new(bytes),
                sensitive,
            });
            0
        },
        || -1,
    )
}

/// Sets the HTTP method (default GET). Returns -1 on invalid input.
#[no_mangle]
pub unsafe extern "C" fn np_request_method(
    req: *mut NativePathRequestHandle,
    method: *const c_char,
) -> i32 {
    guard(
        || {
            let (Some(r), false) = (req.as_mut(), method.is_null()) else {
                return -1;
            };
            let Ok(m) = CStr::from_ptr(method).to_str() else {
                return -1;
            };
            r.method = m.to_string();
            0
        },
        || -1,
    )
}

/// Sets the request body, copied from caller-owned native memory into a wiped buffer.
/// `sensitive` gives the request a connection of its own. Returns -1 on invalid input.
#[no_mangle]
pub unsafe extern "C" fn np_request_body(
    req: *mut NativePathRequestHandle,
    ptr: *const u8,
    len: usize,
    sensitive: bool,
) -> i32 {
    guard(
        || {
            let Some(r) = req.as_mut() else { return -1 };
            if ptr.is_null() && len > 0 {
                return -1;
            }
            let bytes = if len == 0 {
                Vec::new()
            } else {
                std::slice::from_raw_parts(ptr, len).to_vec()
            };
            r.body = Some(SecretBuffer::new(bytes));
            r.sensitive_body = sensitive;
            0
        },
        || -1,
    )
}

#[no_mangle]
pub unsafe extern "C" fn np_request_free(req: *mut NativePathRequestHandle) {
    guard(
        || {
            if !req.is_null() {
                drop(Box::from_raw(req));
            }
        },
        || (),
    )
}

/// Like `np_stream_open`, sending the headers collected in `req` (may be null).
#[no_mangle]
pub unsafe extern "C" fn np_stream_open_with(
    handle: *mut NativePathClientHandle,
    url: *const c_char,
    req: *mut NativePathRequestHandle,
    out_status: *mut u16,
    out_content_length: *mut i64,
    out_error: *mut *mut c_char,
) -> *mut NativePathStreamHandle {
    guard(
        || {
            if handle.is_null()
                || url.is_null()
                || out_status.is_null()
                || out_content_length.is_null()
                || out_error.is_null()
            {
                if !out_error.is_null() {
                    *out_error = error_cstring("Invalid handle, url or out parameter");
                }
                return std::ptr::null_mut();
            }
            let Ok(url_str) = CStr::from_ptr(url).to_str() else {
                *out_error = error_cstring("URL is not valid UTF-8");
                return std::ptr::null_mut();
            };
            let result = match req.as_ref() {
                Some(r) => (*handle).inner.send_stream(
                    &r.method,
                    url_str,
                    &r.headers,
                    r.body.as_ref().map(|b| b.as_slice()),
                    r.sensitive_body,
                ),
                None => (*handle).inner.get_stream_with(url_str, &[]),
            };
            match result {
                Ok(stream) => {
                    *out_status = stream.status;
                    *out_content_length = stream.content_length.map(|n| n as i64).unwrap_or(-1);
                    Box::into_raw(Box::new(NativePathStreamHandle {
                        inner: stream,
                        error: None,
                    }))
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

/// Allocates a zeroed native buffer of `len` bytes for the caller to fill (e.g. with a secret).
/// Release with `np_wipe_and_free(ptr, len, *out_cap)`.
#[no_mangle]
pub unsafe extern "C" fn np_secret_alloc(len: usize, out_cap: *mut usize) -> *mut u8 {
    guard(
        || {
            let (ptr, _len, cap) = SecretBuffer::new(vec![0u8; len]).into_raw_parts();
            if !out_cap.is_null() {
                *out_cap = cap;
            }
            ptr
        },
        || std::ptr::null_mut(),
    )
}

#[cfg(target_os = "macos")]
mod keychain {
    use np_http_client::alloc::secure_wipe;
    use np_http_client::SecretBuffer;
    use std::os::raw::c_void;

    #[link(name = "Security", kind = "framework")]
    extern "C" {
        fn SecKeychainFindGenericPassword(
            keychain_or_array: *const c_void,
            service_len: u32,
            service: *const u8,
            account_len: u32,
            account: *const u8,
            password_len: *mut u32,
            password_data: *mut *mut c_void,
            item: *mut *mut c_void,
        ) -> i32;
        fn SecKeychainItemFreeContent(attr_list: *mut c_void, data: *mut c_void) -> i32;
    }

    /// Copies the password into a wiped buffer, then zeroes the framework's own copy before
    /// handing it back (it is plain `malloc` memory, not covered by the zeroing allocator).
    pub unsafe fn read(service: &[u8], account: &[u8]) -> Result<SecretBuffer, i32> {
        let mut len: u32 = 0;
        let mut data: *mut c_void = std::ptr::null_mut();
        let status = SecKeychainFindGenericPassword(
            std::ptr::null(),
            service.len() as u32,
            service.as_ptr(),
            account.len() as u32,
            account.as_ptr(),
            &mut len,
            &mut data,
            std::ptr::null_mut(),
        );
        if status != 0 {
            return Err(status);
        }
        let mut v = vec![0u8; len as usize];
        if !data.is_null() {
            std::ptr::copy_nonoverlapping(data as *const u8, v.as_mut_ptr(), len as usize);
            secure_wipe(data as *mut u8, len as usize);
            SecKeychainItemFreeContent(std::ptr::null_mut(), data);
        }
        Ok(SecretBuffer::new(v))
    }
}

/// Status returned by `np_keychain_read` on platforms without a supported keychain.
pub const KEYCHAIN_UNSUPPORTED: i32 = i32::MIN;

/// Reads a generic password from the macOS keychain (default search list) into a wiped native
/// buffer. On success returns the pointer and sets `*out_len`/`*out_cap` (release with
/// `np_wipe_and_free`); on failure returns null and sets `*out_status` to the `OSStatus`,
/// or to `KEYCHAIN_UNSUPPORTED` off macOS. The password never passes through a Rust `String`.
#[no_mangle]
pub unsafe extern "C" fn np_keychain_read(
    service: *const u8,
    service_len: usize,
    account: *const u8,
    account_len: usize,
    out_len: *mut usize,
    out_cap: *mut usize,
    out_status: *mut i32,
) -> *mut u8 {
    if service.is_null() || account.is_null() || out_len.is_null() || out_cap.is_null() || out_status.is_null() {
        if !out_status.is_null() {
            *out_status = -50; // errSecParam
        }
        return std::ptr::null_mut();
    }
    #[cfg(target_os = "macos")]
    {
        let service = std::slice::from_raw_parts(service, service_len);
        let account = std::slice::from_raw_parts(account, account_len);
        match keychain::read(service, account) {
            Ok(buf) => {
                let (ptr, len, cap) = buf.into_raw_parts();
                *out_len = len;
                *out_cap = cap;
                *out_status = 0;
                ptr
            }
            Err(status) => {
                *out_status = status;
                std::ptr::null_mut()
            }
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (service_len, account_len);
        *out_status = KEYCHAIN_UNSUPPORTED;
        std::ptr::null_mut()
    }
}

/// Test hook: counts occurrences of an XOR-masked value in this process's memory.
/// Returns u64::MAX where unsupported.
#[no_mangle]
pub unsafe extern "C" fn np_test_scan_memory(masked: *const u8, len: usize, mask: u8) -> u64 {
    guard(
        || {
            if masked.is_null() {
                return 0;
            }
            np_http_client::scan::count_matches(std::slice::from_raw_parts(masked, len), mask)
        },
        || u64::MAX,
    )
}

/// Test hook: copies up to `n` match addresses from the last scan into `out`; returns how many.
#[no_mangle]
pub unsafe extern "C" fn np_test_scan_hits(out: *mut u64, n: usize) -> usize {
    guard(
        || {
            let hits = np_http_client::scan::last_hits();
            let k = hits.len().min(n);
            if !out.is_null() {
                std::ptr::copy_nonoverlapping(hits.as_ptr(), out, k);
            }
            k
        },
        || 0,
    )
}

/// Strict isolation: every request uses its own connection, closed when it finishes, so network
/// buffers are freed (and zeroed) instead of being reused. Sensitive-header requests are isolated
/// regardless.
#[no_mangle]
pub unsafe extern "C" fn np_client_set_isolate_all(
    handle: *mut NativePathClientHandle,
    enabled: bool,
) {
    guard(
        || {
            if let Some(h) = handle.as_ref() {
                h.inner.set_isolate_all(enabled);
            }
        },
        || (),
    )
}

/// Response headers as `name\0value\0` pairs. The pointer is borrowed from the stream (wiped when
/// the stream is freed); do not free it.
#[no_mangle]
pub unsafe extern "C" fn np_stream_headers(
    handle: *mut NativePathStreamHandle,
    out_len: *mut usize,
) -> *const u8 {
    guard(
        || {
            let Some(h) = handle.as_ref() else {
                return std::ptr::null();
            };
            let b = h.inner.headers_packed();
            if !out_len.is_null() {
                *out_len = b.len();
            }
            b.as_ptr()
        },
        || std::ptr::null(),
    )
}

/// Test hook: panics inside the guard. Returns -1 when the panic was contained, as every entry
/// point does; the JVM must survive the call.
#[no_mangle]
pub extern "C" fn np_test_panic() -> i32 {
    guard(|| panic!("np_test_panic"), || -1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn panics_become_error_results() {
        assert_eq!(np_test_panic(), -1);
    }
}
