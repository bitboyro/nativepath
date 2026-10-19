pub use np_memory::{alloc, scan, SecretBuffer};

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use np_memory::alloc::secure_wipe;
use np_memory::wipe_vec;

#[derive(Clone, Debug)]
pub struct ClientConfig {
    pub trust_self_signed: bool,
    pub timeout_ms: u64,
    /// Upper bound on a response body; larger responses are rejected.
    pub max_body_bytes: usize,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            trust_self_signed: false,
            timeout_ms: 30_000,
            max_body_bytes: 256 * 1024 * 1024,
        }
    }
}

/// Where time went inside a buffered `get`, measured inside the Rust engine (nanoseconds).
#[derive(Clone, Copy, Debug, Default)]
pub struct Timings {
    /// Request send until response headers arrive (connect, TLS, server think time).
    pub headers_ns: u64,
    /// Reading the body from the network.
    pub body_ns: u64,
    /// Allocating and copying the body into the final `SecretBuffer`.
    pub assemble_ns: u64,
}

/// A request header whose value is held in a wiped buffer. `sensitive` headers are marked so that
/// HTTP/2 never indexes them and debug output redacts them.
pub struct RequestHeader {
    pub name: String,
    pub value: SecretBuffer,
    pub sensitive: bool,
}

pub struct NativePathResponse {
    pub status: u16,
    pub body: SecretBuffer,
    pub timings: Timings,
}

pub struct NativePathClient {
    runtime: Arc<tokio::runtime::Runtime>,
    config: Arc<ClientConfig>,
    insecure_flag: Arc<AtomicBool>,
    max_body: Arc<AtomicUsize>,
    /// Strict isolation: every request gets its own connection, closed when it finishes.
    isolate_all: AtomicBool,
    /// Shared clients, rebuilt only when the insecure flag changes: slot 0 pools connections,
    /// slot 1 never keeps one alive. A kept-alive connection retains the bytes of the last
    /// request (including header values) in its reused write buffer, so requests carrying
    /// sensitive headers always use slot 1; the buffers are then freed, and zeroed, at close.
    http: Mutex<[Option<(bool, reqwest::Client)>; 2]>,
}

/// Chunks at least this large are returned zero-copy, straight from the network buffer.
const ZERO_COPY_MIN: usize = 64 * 1024;
/// Smaller chunks are gathered into one buffer of this size to amortise per-call overhead.
const COALESCE_CAP: usize = 256 * 1024;
/// While coalescing, stop waiting for more data after this long, so slow streams stay timely.
const COALESCE_WAIT: std::time::Duration = std::time::Duration::from_millis(1);

/// A response whose body is consumed incrementally. Large network chunks are handed out
/// without copying; runs of small ones (e.g. chunked transfer encoding) are gathered into a
/// reusable 256 KB buffer so each call carries a useful amount of data. Buffers are wiped on
/// reuse and on drop (network chunks are wiped by the global allocator when freed), and memory
/// use stays flat for any body size.
pub struct NativePathStream {
    runtime: Arc<tokio::runtime::Runtime>,
    response: Option<reqwest::Response>,
    /// A chunk received while coalescing that did not fit; served first on the next call.
    pending: Option<bytes::Bytes>,
    /// The chunk currently exposed zero-copy, if any.
    current: Option<bytes::Bytes>,
    /// Coalescing buffer; holds the current output when `current` is `None`.
    buffer: Vec<u8>,
    /// Bytes of a declared `Content-Length` not yet received from the network. Once it reaches
    /// zero the body is complete, so the stream ends without waiting for the connection layer
    /// to signal end-of-body (that wait cost tens of microseconds and sometimes milliseconds).
    remaining: Option<u64>,
    /// Response headers as `name\0value\0` pairs in a wiped buffer (values may be secret).
    headers: SecretBuffer,
    pub status: u16,
    pub content_length: Option<u64>,
}

/// Accounts for a chunk received from the network and drops the response once the declared
/// length has been delivered in full.
fn note_received(remaining: &mut Option<u64>, response: &mut Option<reqwest::Response>, len: usize) {
    if let Some(r) = remaining.as_mut() {
        *r = r.saturating_sub(len as u64);
        if *r == 0 {
            *response = None;
        }
    }
}

async fn read_nonempty(
    response: &mut Option<reqwest::Response>,
    remaining: &mut Option<u64>,
) -> Result<Option<bytes::Bytes>, reqwest::Error> {
    let Some(r) = response.as_mut() else { return Ok(None) };
    loop {
        match r.chunk().await? {
            Some(c) if c.is_empty() => continue,
            Some(c) => {
                note_received(remaining, response, c.len());
                return Ok(Some(c));
            }
            None => {
                *response = None;
                return Ok(None);
            }
        }
    }
}

impl NativePathStream {
    /// Response headers packed as `name\0value\0` pairs; valid until the stream is dropped.
    pub fn headers_packed(&self) -> &[u8] {
        self.headers.as_slice()
    }

    fn clear_buffer(&mut self) {
        secure_wipe(self.buffer.as_mut_ptr(), self.buffer.len());
        self.buffer.clear();
    }

    /// Next network chunk exactly as received (no coalescing, no copy).
    fn next_raw(&mut self) -> Result<Option<bytes::Bytes>, Box<dyn std::error::Error>> {
        if let Some(c) = self.pending.take() {
            return Ok(Some(c));
        }
        let Self { runtime, response, remaining, .. } = self;
        Ok(runtime.block_on(read_nonempty(response, remaining))?)
    }

    /// Advances to the next piece of the body. `Ok(None)` means end of body.
    /// The returned slice is valid until the next call or until the stream is dropped.
    pub fn next_chunk(&mut self) -> Result<Option<&[u8]>, Box<dyn std::error::Error>> {
        self.current = None; // drops (and wipes) the previous chunk
        self.clear_buffer();

        let Self { runtime, response, pending, current, buffer, remaining, .. } = self;
        let has_data = runtime.block_on(async {
            let first = match pending.take() {
                Some(c) => c,
                None => match read_nonempty(response, remaining).await? {
                    Some(c) => c,
                    None => return Ok::<bool, reqwest::Error>(false),
                },
            };
            if first.len() >= ZERO_COPY_MIN {
                *current = Some(first);
                return Ok(true);
            }
            if buffer.capacity() == 0 {
                buffer.reserve_exact(COALESCE_CAP);
            }
            buffer.extend_from_slice(&first);
            drop(first);
            while let Some(r) = response.as_mut() {
                match tokio::time::timeout(COALESCE_WAIT, r.chunk()).await {
                    Ok(Ok(Some(c))) if c.is_empty() => continue,
                    Ok(Ok(Some(c))) => {
                        note_received(remaining, response, c.len());
                        if buffer.len() + c.len() > COALESCE_CAP {
                            *pending = Some(c);
                            break;
                        }
                        buffer.extend_from_slice(&c);
                        if buffer.len() >= COALESCE_CAP {
                            break;
                        }
                    }
                    Ok(Ok(None)) => {
                        *response = None;
                        break;
                    }
                    Ok(Err(e)) => return Err(e),
                    Err(_) => break, // data paused: hand over what we have
                }
            }
            Ok(true)
        })?;

        if !has_data {
            return Ok(None);
        }
        Ok(Some(match self.current.as_deref() {
            Some(c) => c,
            None => &self.buffer[..],
        }))
    }
}

impl Drop for NativePathStream {
    fn drop(&mut self) {
        let _guard = self.runtime.enter();
        self.current = None;
        self.pending = None;
        self.response = None;
        wipe_vec(&mut self.buffer);
    }
}

impl NativePathClient {
    pub fn new(config: ClientConfig) -> Result<Self, Box<dyn std::error::Error>> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()?;

        let insecure_flag = Arc::new(AtomicBool::new(config.trust_self_signed));
        let max_body = Arc::new(AtomicUsize::new(config.max_body_bytes));

        Ok(Self {
            runtime: Arc::new(runtime),
            config: Arc::new(config),
            insecure_flag,
            max_body,
            isolate_all: AtomicBool::new(false),
            http: Mutex::new([None, None]),
        })
    }

    fn http_client(&self, insecure: bool, isolated: bool) -> Result<reqwest::Client, reqwest::Error> {
        let slot = isolated as usize;
        let mut guard = self.http.lock().unwrap();
        if let Some((built_insecure, client)) = guard[slot].as_ref() {
            if *built_insecure == insecure {
                return Ok(client.clone());
            }
        }
        // `timeout_ms` bounds connecting and each individual read, not the whole transfer,
        // so large bodies are not cut off on slow links.
        let limit = std::time::Duration::from_millis(self.config.timeout_ms);
        let mut builder = reqwest::Client::builder()
            .danger_accept_invalid_certs(insecure)
            .connect_timeout(limit)
            .read_timeout(limit);
        if isolated {
            builder = builder.pool_max_idle_per_host(0);
        }
        let client = builder.build()?;
        guard[slot] = Some((insecure, client.clone()));
        Ok(client)
    }

    /// Strict isolation: no request reuses a connection (costs a handshake per request and, at
    /// high request rates, can exhaust ephemeral ports). Requests with sensitive headers are
    /// isolated regardless of this setting.
    pub fn set_isolate_all(&self, enabled: bool) {
        self.isolate_all.store(enabled, Ordering::SeqCst);
    }

    pub fn set_trust_self_signed(&self, trust: bool) {
        self.insecure_flag.store(trust, Ordering::SeqCst);
    }

    pub fn is_trust_self_signed(&self) -> bool {
        self.insecure_flag.load(Ordering::SeqCst)
    }

    /// Maximum body size accepted by buffered `get`; larger bodies must use `get_stream`.
    pub fn set_max_body_bytes(&self, max: usize) {
        self.max_body.store(max, Ordering::SeqCst);
    }

    pub fn max_body_bytes(&self) -> usize {
        self.max_body.load(Ordering::SeqCst)
    }

    /// Starts a request and returns once headers arrive; the body is read via the stream.
    pub fn get_stream(&self, url: &str) -> Result<NativePathStream, Box<dyn std::error::Error>> {
        self.get_stream_with(url, &[])
    }

    /// Like `get_stream`, with extra request headers (values may be secret).
    pub fn get_stream_with(
        &self,
        url: &str,
        headers: &[RequestHeader],
    ) -> Result<NativePathStream, Box<dyn std::error::Error>> {
        self.send_stream("GET", url, headers, None, false)
    }

    /// Sends a request with any method and an optional body; the response body is read via the
    /// stream. `sensitive_body` isolates the connection like a sensitive header does, because a
    /// kept-alive connection retains the last request's bytes in its write buffer. The body is
    /// copied into a buffer owned by the HTTP layer, which the wiping allocator zeroes when freed.
    pub fn send_stream(
        &self,
        method: &str,
        url: &str,
        headers: &[RequestHeader],
        body: Option<&[u8]>,
        sensitive_body: bool,
    ) -> Result<NativePathStream, Box<dyn std::error::Error>> {
        let insecure = self.insecure_flag.load(Ordering::SeqCst);
        let isolated = self.isolate_all.load(Ordering::SeqCst)
            || sensitive_body
            || headers.iter().any(|h| h.sensitive);
        let client = self.http_client(insecure, isolated)?;
        let url_owned = url.to_string();
        let method = reqwest::Method::from_bytes(method.as_bytes())?;

        let mut request = client.request(method, &url_owned);
        if let Some(b) = body {
            request = request.body(bytes::Bytes::copy_from_slice(b));
        }
        for h in headers {
            let name = reqwest::header::HeaderName::from_bytes(h.name.as_bytes())?;
            let mut value = reqwest::header::HeaderValue::from_bytes(h.value.as_slice())?;
            value.set_sensitive(h.sensitive);
            request = request.header(name, value);
        }

        let response = self.runtime.block_on(async move { request.send().await })?;
        let response_len = response.content_length();
        let mut packed = Vec::new();
        for (name, value) in response.headers() {
            packed.extend_from_slice(name.as_str().as_bytes());
            packed.push(0);
            packed.extend_from_slice(value.as_bytes());
            packed.push(0);
        }
        Ok(NativePathStream {
            headers: SecretBuffer::new(packed),
            runtime: Arc::clone(&self.runtime),
            status: response.status().as_u16(),
            content_length: response.content_length(),
            response: Some(response),
            pending: None,
            current: None,
            buffer: Vec::new(),
            remaining: response_len,
        })
    }

    pub fn get(&self, url: &str) -> Result<NativePathResponse, Box<dyn std::error::Error>> {
        let max_body = self.max_body_bytes();
        let too_big = move |what: &str| -> Box<dyn std::error::Error> {
            format!("response body {what} exceeds max_body_bytes ({max_body}); use get_stream for large bodies")
                .into()
        };

        let t_start = std::time::Instant::now();
        let mut stream = self.get_stream(url)?;
        let headers_ns = t_start.elapsed().as_nanos() as u64;
        let status = stream.status;
        let mut assemble_ns = 0u64;
        let t_body = std::time::Instant::now();

        let body = match stream.content_length {
            // Known size: one exact-size allocation, chunks copied in once.
            Some(len) => {
                if len as usize > max_body {
                    return Err(too_big("(declared by Content-Length)"));
                }
                let mut buf: Vec<u8> = Vec::with_capacity(len as usize);
                while let Some(chunk) = stream.next_raw()? {
                    if buf.len() + chunk.len() > max_body {
                        wipe_vec(&mut buf);
                        return Err(too_big("(while reading)"));
                    }
                    let t = std::time::Instant::now();
                    buf.extend_from_slice(&chunk);
                    assemble_ns += t.elapsed().as_nanos() as u64;
                }
                buf
            }
            // Unknown size: keep the network chunks (no copying, no buffer doubling),
            // then concatenate once into an exact-size buffer.
            None => {
                let mut chunks: Vec<bytes::Bytes> = Vec::new();
                let mut total = 0usize;
                while let Some(chunk) = stream.next_raw()? {
                    total += chunk.len();
                    if total > max_body {
                        return Err(too_big("(while reading)"));
                    }
                    chunks.push(chunk);
                }
                let t = std::time::Instant::now();
                let mut buf: Vec<u8> = Vec::with_capacity(total);
                for c in &chunks {
                    buf.extend_from_slice(c);
                }
                assemble_ns += t.elapsed().as_nanos() as u64;
                buf
            }
        };

        let body_ns = (t_body.elapsed().as_nanos() as u64).saturating_sub(assemble_ns);
        Ok(NativePathResponse {
            status,
            body: SecretBuffer::new(body),
            timings: Timings { headers_ns, body_ns, assemble_ns },
        })
    }
}
