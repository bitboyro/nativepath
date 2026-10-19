//! NativePath server engine: accepts HTTP/1.1 connections, reads each request into wiped native
//! buffers and hands it to a foreign (Java) worker, which replies through [`ServerRequest::respond`].
//!
//! The server is split into **lanes**, like Netty's event loops. An acceptor thread hands each new
//! connection to a lane (round-robin); after that, everything for the connection (TLS, parsing,
//! writing the response) happens on the one thread that drives the lane. Each lane is driven by
//! exactly one worker calling [`Lane::next_request`]: the call runs the lane's event loop on the
//! worker's own thread until a request is ready, so a request never crosses threads. Native code
//! never calls back into the JVM. A request whose worker never replies (dropped without `respond`)
//! is answered with 500.
//!
//! A worker may also answer from another thread, or stream the response
//! ([`ServerRequest::respond_streaming`]): replies wake the lane's event loop, which writes them
//! while its driver waits in [`Lane::next_request`].

use bytes::Bytes;
use np_memory::{wipe_vec, SecretBuffer};
use http_body_util::{BodyExt, Full};
use hyper::body::{Body, Frame, Incoming, SizeHint};
use hyper::header::{HeaderName, HeaderValue, CONNECTION, CONTENT_LENGTH, TRANSFER_ENCODING};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::TokioIo;
use std::collections::VecDeque;
use std::convert::Infallible;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::Duration;
use std::time::Instant;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, oneshot, Notify};

#[derive(Clone, Debug)]
pub struct ServerConfig {
    pub addr: SocketAddr,
    /// Requests with a larger body are answered with 413 and never reach a worker.
    pub max_body_bytes: usize,
    /// Lanes (event loops), each driven by one worker thread. Default: available cores.
    pub lanes: usize,
    /// Terminate TLS in the engine (decrypted data never leaves wiped native memory).
    pub tls: Option<TlsFiles>,
    /// Requests a worker may take in a row without running its lane's event loop in between, while
    /// more are already waiting (1: run it before every request). Replies to a batch are written
    /// when the loop next runs, so a larger batch trades a little latency for less overhead.
    pub batch: usize,
    /// Times a worker with nothing to take lets its lane's connections run again before it goes to
    /// sleep. Under load the next request usually arrives within one round, and catching it there
    /// costs one system call instead of a wake-up and a sleep (two); an idle lane pays one extra
    /// call per spin.
    pub spin: usize,
    /// Lets a worker with nothing to do take requests waiting on another lane whose worker is busy.
    /// The reply is still written by the owning lane.
    pub work_stealing: bool,
    /// Requests that may wait in one lane's queue; beyond it new requests are answered 503 at once
    /// (0: no limit).
    pub max_queued: usize,
    /// What one client can hold: time per phase, connections, buffered body bytes.
    pub limits: Limits,
}

/// Bounds on what clients can hold, so slow or numerous clients cannot exhaust the server.
#[derive(Clone, Debug)]
pub struct Limits {
    /// From the first byte of a request head until it is parsed; then the connection is closed.
    pub header_read_timeout: Duration,
    /// A keep-alive connection waiting for its next request, or a response write making no
    /// progress, is closed after this long.
    pub idle_timeout: Duration,
    /// Reading the whole request body; slower bodies get 408.
    pub body_read_timeout: Duration,
    pub tls_handshake_timeout: Duration,
    /// Request line plus headers; larger heads get 431.
    pub max_header_bytes: usize,
    /// Open connections over all lanes; further ones are closed when accepted.
    pub max_connections: usize,
    /// Request body bytes buffered over all lanes (read or declared, not yet answered); requests
    /// that would exceed it get 503.
    pub max_inflight_body_bytes: usize,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            header_read_timeout: Duration::from_secs(10),
            idle_timeout: Duration::from_secs(60),
            body_read_timeout: Duration::from_secs(30),
            tls_handshake_timeout: Duration::from_secs(10),
            max_header_bytes: 64 * 1024,
            max_connections: 10_000,
            max_inflight_body_bytes: 256 * 1024 * 1024,
        }
    }
}

/// Limits plus the counters they are checked against, shared by the acceptor and all lanes.
struct Shared {
    limits: Limits,
    connections: AtomicUsize,
    inflight_body: AtomicUsize,
}

/// One accepted connection counted against `max_connections`; released when dropped.
struct ConnPermit(Arc<Shared>);

impl ConnPermit {
    fn try_new(shared: &Arc<Shared>) -> Option<Self> {
        let max = shared.limits.max_connections;
        shared
            .connections
            .try_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                (n < max).then_some(n + 1)
            })
            .ok()
            .map(|_| Self(Arc::clone(shared)))
    }
}

impl Drop for ConnPermit {
    fn drop(&mut self) {
        self.0.connections.fetch_sub(1, Ordering::AcqRel);
    }
}

/// Body bytes one request holds against `max_inflight_body_bytes`; released when dropped.
struct BodyBudget {
    shared: Option<Arc<Shared>>,
    held: usize,
}

impl BodyBudget {
    /// Raises the reservation to `total` bytes; false if that would exceed the shared limit.
    fn reserve(&mut self, total: usize) -> bool {
        let Some(shared) = &self.shared else {
            return true;
        };
        let Some(more) = total.checked_sub(self.held).filter(|n| *n > 0) else {
            return true;
        };
        let max = shared.limits.max_inflight_body_bytes;
        let ok = shared
            .inflight_body
            .try_update(Ordering::AcqRel, Ordering::Acquire, |n| {
                n.checked_add(more).filter(|t| *t <= max)
            })
            .is_ok();
        if ok {
            self.held = total;
        }
        ok
    }
}

impl Drop for BodyBudget {
    fn drop(&mut self) {
        if let Some(shared) = &self.shared {
            shared.inflight_body.fetch_sub(self.held, Ordering::AcqRel);
        }
    }
}

/// PEM files for TLS. The key is read by the engine and never passes through the JVM.
#[derive(Clone, Debug)]
pub struct TlsFiles {
    pub cert_chain: PathBuf,
    pub private_key: PathBuf,
}

fn tls_acceptor(files: &TlsFiles) -> std::io::Result<tokio_rustls::TlsAcceptor> {
    use rustls_pki_types::pem::PemObject;
    use rustls_pki_types::{CertificateDer, PrivateKeyDer};
    use tokio_rustls::rustls;
    let bad = |what: &str, e: &dyn std::fmt::Display| {
        std::io::Error::new(std::io::ErrorKind::InvalidInput, format!("{what}: {e}"))
    };
    let certs = CertificateDer::pem_file_iter(&files.cert_chain)
        .and_then(|it| it.collect::<Result<Vec<_>, _>>())
        .map_err(|e| {
            bad(
                &format!("certificate chain {}", files.cert_chain.display()),
                &e,
            )
        })?;
    if certs.is_empty() {
        return Err(bad("certificate chain", &"no certificates found"));
    }
    let key = PrivateKeyDer::from_pem_file(&files.private_key)
        .map_err(|e| bad(&format!("private key {}", files.private_key.display()), &e))?;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| bad("TLS configuration", &e))?
        .with_no_client_auth()
        .with_single_cert(certs, key)
        .map_err(|e| bad("certificate/key", &e))?;
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(tokio_rustls::TlsAcceptor::from(Arc::new(config)))
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            addr: SocketAddr::from(([127, 0, 0, 1], 0)),
            max_body_bytes: 16 * 1024 * 1024,
            lanes: std::thread::available_parallelism()
                .map(|n| n.get())
                .unwrap_or(4),
            tls: None,
            batch: 1,
            spin: 0,
            work_stealing: false,
            max_queued: 0,
            limits: Limits::default(),
        }
    }
}

/// The reply a worker produces for one request.
struct Reply {
    status: u16,
    headers: Vec<(HeaderName, HeaderValue)>,
    body: EngineBody,
    answered_at: Instant,
}

/// Chunks a streamed response may have in flight before [`ResponseWriter::write`] blocks.
const STREAM_CHUNKS: usize = 8;

/// A response body: whole, or streamed by a worker through a [`ResponseWriter`].
enum EngineBody {
    Full(Full<Bytes>),
    /// An `Err` chunk aborts the connection, so a truncated response never looks complete.
    Stream(mpsc::Receiver<Result<Bytes, std::io::Error>>),
}

impl Body for EngineBody {
    type Data = Bytes;
    type Error = std::io::Error;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, std::io::Error>>> {
        match self.get_mut() {
            EngineBody::Full(f) => Pin::new(f).poll_frame(cx).map_err(|never| match never {}),
            EngineBody::Stream(rx) => rx.poll_recv(cx).map(|c| c.map(|r| r.map(Frame::data))),
        }
    }

    fn is_end_stream(&self) -> bool {
        match self {
            EngineBody::Full(f) => f.is_end_stream(),
            EngineBody::Stream(_) => false,
        }
    }

    fn size_hint(&self) -> SizeHint {
        match self {
            EngineBody::Full(f) => f.size_hint(),
            EngineBody::Stream(_) => SizeHint::default(),
        }
    }
}

/// Writes a streamed response body ([`ServerRequest::respond_streaming`]). Dropping it (or
/// [`finish`](Self::finish)) ends the body; [`abort`](Self::abort) closes the connection instead.
pub struct ResponseWriter {
    tx: mpsc::Sender<Result<Bytes, std::io::Error>>,
}

impl ResponseWriter {
    /// Copies `data` into the response. Blocks while the connection already has
    /// [`STREAM_CHUNKS`] chunks waiting, so it must not run on the thread driving this request's
    /// lane (nothing would drain them) nor inside an async runtime. Fails once the client is gone.
    pub fn write(&self, data: &[u8]) -> Result<(), String> {
        if data.is_empty() {
            return Ok(());
        }
        self.tx
            .blocking_send(Ok(Bytes::copy_from_slice(data)))
            .map_err(|_| "client gone".to_string())
    }

    /// Ends the body normally.
    pub fn finish(self) {}

    /// Ends the response by closing the connection (the client sees a truncated response).
    pub fn abort(self) {
        let _ = self
            .tx
            .blocking_send(Err(std::io::Error::other("response aborted")));
    }
}

/// Where a request's time goes inside the engine, summed over requests (nanoseconds). Always on:
/// a few clock reads and relaxed atomic adds per request.
#[derive(Default)]
pub struct EngineStats {
    /// The same five phases as `read_ns`, `queue_ns`, `handler_ns`, `respond_ns`, `reply_ns` (in
    /// that order), kept as histograms so the tail shows, not only the mean.
    pub hist: [PhaseHist; PHASES],
    /// Requests answered by a worker.
    pub requests: AtomicU64,
    /// Engine handler entry (head parsed) until queued for the worker: body read and header packing.
    pub read_ns: AtomicU64,
    /// Queued until the worker took it (a busy lane makes this grow).
    pub queue_ns: AtomicU64,
    /// Taken by the worker until it answered: the Java side (adapter, Spring, application).
    pub handler_ns: AtomicU64,
    /// Inside `respond`: validating headers and copying the body.
    pub respond_ns: AtomicU64,
    /// Answered until the engine's connection task picked the reply up to write it.
    pub reply_ns: AtomicU64,
    /// Requests answered 503 because their lane's queue was full.
    pub shed_requests: AtomicU64,
    /// Connections answered 503 and closed because the connection limit was reached.
    pub shed_connections: AtomicU64,
    /// Requests taken by another lane's worker (work stealing).
    pub stolen: AtomicU64,
}

/// Phases kept as histograms: read, queue, handler, respond, reply.
pub const PHASES: usize = 5;
/// Buckets per phase: four per power of two of nanoseconds, up to about 2^40 ns.
pub const HIST_BUCKETS: usize = 160;

/// Counts of durations in log-scale buckets (four per octave, so a bucket spans about 19% to 25%).
/// One relaxed atomic add per recorded duration.
pub struct PhaseHist([AtomicU64; HIST_BUCKETS]);

impl Default for PhaseHist {
    fn default() -> Self {
        PhaseHist(std::array::from_fn(|_| AtomicU64::new(0)))
    }
}

impl PhaseHist {
    fn bucket(ns: u64) -> usize {
        if ns < 4 {
            return ns as usize;
        }
        let octave = 63 - ns.leading_zeros() as usize;
        let sub = ((ns >> (octave - 2)) & 3) as usize;
        ((octave - 1) * 4 + sub).min(HIST_BUCKETS - 1)
    }

    fn record(&self, ns: u64) {
        self.0[Self::bucket(ns)].fetch_add(1, Ordering::Relaxed);
    }

    /// The bucket counts so far.
    pub fn counts(&self) -> [u64; HIST_BUCKETS] {
        std::array::from_fn(|i| self.0[i].load(Ordering::Relaxed))
    }
}

impl EngineStats {
    fn add(counter: &AtomicU64, hist: &PhaseHist, since: Instant) {
        let ns = since.elapsed().as_nanos() as u64;
        counter.fetch_add(ns, Ordering::Relaxed);
        hist.record(ns);
    }

    /// Histogram counts, `PHASES` rows of `HIST_BUCKETS`, flattened.
    pub fn hist_snapshot(&self) -> Vec<u64> {
        self.hist.iter().flat_map(|h| h.counts()).collect()
    }

    /// `[requests, read_ns, queue_ns, handler_ns, respond_ns, reply_ns, shed_requests,
    /// shed_connections, stolen]`.
    pub fn snapshot(&self) -> [u64; 9] {
        [
            self.requests.load(Ordering::Relaxed),
            self.read_ns.load(Ordering::Relaxed),
            self.queue_ns.load(Ordering::Relaxed),
            self.handler_ns.load(Ordering::Relaxed),
            self.respond_ns.load(Ordering::Relaxed),
            self.reply_ns.load(Ordering::Relaxed),
            self.shed_requests.load(Ordering::Relaxed),
            self.shed_connections.load(Ordering::Relaxed),
            self.stolen.load(Ordering::Relaxed),
        ]
    }
}

/// One request, owned by the worker that took it. Head and body live in wiped native buffers.
pub struct ServerRequest {
    pub method: String,
    /// Path and query, as sent (e.g. `/api/users?id=7`).
    pub target: String,
    headers: SecretBuffer,
    body: SecretBuffer,
    reply: Option<oneshot::Sender<Reply>>,
    /// The lane whose connection the request came from (its reply goes back there).
    lane: Arc<LaneQueue>,
    queued_at: Instant,
    taken_at: Instant,
    /// The client's address, when known.
    pub peer: Option<SocketAddr>,
    /// The local address the connection arrived on, when known.
    pub local: Option<SocketAddr>,
    // Released when the request is answered or dropped.
    _budget: BodyBudget,
}

pub enum Next {
    Request(ServerRequest),
    Timeout,
    Stopped,
}

impl ServerRequest {
    /// Request headers as `name\0value\0` pairs (names lower case), the client's format.
    pub fn headers_packed(&self) -> &[u8] {
        self.headers.as_slice()
    }

    pub fn body(&self) -> &[u8] {
        self.body.as_slice()
    }

    /// Moves the body out (leaving it empty), so a foreign owner can control when it is freed.
    pub fn take_body(&mut self) -> SecretBuffer {
        std::mem::replace(&mut self.body, SecretBuffer::new(Vec::new()))
    }

    /// Moves the packed headers out, like [`take_body`](Self::take_body).
    pub fn take_headers(&mut self) -> SecretBuffer {
        std::mem::replace(&mut self.headers, SecretBuffer::new(Vec::new()))
    }

    /// Sends the response and releases (wipes) the request. `headers` is `name\0value\0` pairs;
    /// `Content-Length` and `Transfer-Encoding` are ignored (the server sets framing itself).
    /// Fails if a header is invalid; the client then gets 500.
    pub fn respond(mut self, status: u16, headers: &[u8], body: &[u8]) -> Result<(), String> {
        let start = Instant::now();
        EngineStats::add(&self.lane.stats.handler_ns, &self.lane.stats.hist[2], self.taken_at);
        let reply = self.reply.take().ok_or("already answered")?;
        let parsed = parse_packed_headers(headers)?;
        if StatusCode::from_u16(status).is_err() {
            return Err(format!("invalid status {status}"));
        }
        let body = EngineBody::Full(Full::new(Bytes::copy_from_slice(body)));
        EngineStats::add(&self.lane.stats.respond_ns, &self.lane.stats.hist[3], start);
        // The client may have gone; that is not the worker's error.
        let _ = reply.send(Reply {
            status,
            headers: parsed,
            body,
            answered_at: Instant::now(),
        });
        self.lane.replied.notify_one();
        Ok(())
    }

    /// Sends the status and headers now and returns a writer for the body, for responses too large
    /// to buffer or produced over time. With `content_length` the body must have exactly that many
    /// bytes (else the connection is closed); without it the response is chunked. Releases the
    /// request like [`respond`](Self::respond). Fails if a header is invalid; the client then gets 500.
    pub fn respond_streaming(
        mut self,
        status: u16,
        headers: &[u8],
        content_length: Option<u64>,
    ) -> Result<ResponseWriter, String> {
        let start = Instant::now();
        EngineStats::add(&self.lane.stats.handler_ns, &self.lane.stats.hist[2], self.taken_at);
        let reply = self.reply.take().ok_or("already answered")?;
        let mut parsed = parse_packed_headers(headers)?;
        if StatusCode::from_u16(status).is_err() {
            return Err(format!("invalid status {status}"));
        }
        if let Some(n) = content_length {
            parsed.push((CONTENT_LENGTH, HeaderValue::from(n)));
        }
        let (tx, rx) = mpsc::channel(STREAM_CHUNKS);
        EngineStats::add(&self.lane.stats.respond_ns, &self.lane.stats.hist[3], start);
        // If the client has gone, writes fail instead.
        let _ = reply.send(Reply {
            status,
            headers: parsed,
            body: EngineBody::Stream(rx),
            answered_at: Instant::now(),
        });
        self.lane.replied.notify_one();
        Ok(ResponseWriter { tx })
    }

    /// A request that did not come from the network, for benchmarks of the worker side: answering
    /// it works as usual, but the reply goes nowhere.
    #[doc(hidden)]
    pub fn synthetic(method: &str, target: &str, headers_packed: Vec<u8>, body: Vec<u8>) -> Self {
        let now = Instant::now();
        Self {
            method: method.to_string(),
            target: target.to_string(),
            headers: SecretBuffer::new(headers_packed),
            body: SecretBuffer::new(body),
            reply: Some(oneshot::channel().0),
            lane: Arc::new(LaneQueue::new(Arc::new(EngineStats::default()), 0, None)),
            queued_at: now,
            taken_at: now,
            peer: None,
            local: None,
            _budget: BodyBudget {
                shared: None,
                held: 0,
            },
        }
    }
}

fn parse_packed_headers(packed: &[u8]) -> Result<Vec<(HeaderName, HeaderValue)>, String> {
    let mut out = Vec::new();
    let mut parts = packed.split(|b| *b == 0);
    while let Some(name) = parts.next() {
        if name.is_empty() {
            break;
        }
        let value = parts.next().ok_or("header without value")?;
        let name = HeaderName::from_bytes(name).map_err(|_| "invalid header name")?;
        if name == CONTENT_LENGTH || name == TRANSFER_ENCODING {
            continue;
        }
        let value = HeaderValue::from_bytes(value).map_err(|_| "invalid header value")?;
        out.push((name, value));
    }
    Ok(out)
}


/// A connection handed to a lane by the acceptor, with its place under the connection limit.
struct Accepted {
    stream: std::net::TcpStream,
    permit: ConnPermit,
}

/// A lane's shared state: requests parsed by its connections and not yet taken, connections not
/// yet started, and the signals its worker waits on.
struct LaneQueue {
    ready: Mutex<VecDeque<ServerRequest>>,
    incoming: Mutex<Vec<Accepted>>,
    /// The worker's one wake-up signal: a request queued, a connection handed over, or stop.
    wake: Notify,
    /// Poked on every answer, for [`Lane::drive`].
    replied: Notify,
    /// The worker holds a request; only busy lanes are stolen from.
    busy: AtomicBool,
    max_queued: usize,
    stats: Arc<EngineStats>,
    /// With work stealing: wakes one idle worker of any lane.
    steal: Option<Arc<Notify>>,
}

impl LaneQueue {
    fn new(stats: Arc<EngineStats>, max_queued: usize, steal: Option<Arc<Notify>>) -> Self {
        Self {
            ready: Mutex::new(VecDeque::new()),
            incoming: Mutex::new(Vec::new()),
            wake: Notify::new(),
            replied: Notify::new(),
            busy: AtomicBool::new(false),
            max_queued,
            stats,
            steal,
        }
    }

    fn full(&self, queued: usize) -> bool {
        self.max_queued != 0 && queued >= self.max_queued
    }

    fn pop(&self) -> Option<ServerRequest> {
        let mut r = self.ready.lock().ok()?.pop_front()?;
        EngineStats::add(&self.stats.queue_ns, &self.stats.hist[1], r.queued_at);
        r.taken_at = Instant::now();
        Some(r)
    }
}

/// One event loop. Driven only by the thread inside [`Lane::next_request`] (or [`Lane::drive`]).
pub struct Lane {
    runtime: Mutex<Option<tokio::runtime::Runtime>>,
    queue: Arc<LaneQueue>,
    /// Every lane's queue, to steal from (empty without work stealing).
    all: Vec<Arc<LaneQueue>>,
    index: usize,
    stopped: Arc<AtomicBool>,
    tls: Option<tokio_rustls::TlsAcceptor>,
    max_body: usize,
    batch: usize,
    spin: usize,
    /// Requests taken since the event loop last ran. Only the driver touches it.
    streak: AtomicUsize,
    shared: Arc<Shared>,
}

impl Lane {
    /// Returns the next request, running this lane's event loop on the calling thread until one is
    /// ready (or `timeout` passes, or the server stops). Responses answered since the loop last ran
    /// are written there too. Only one thread may drive a lane at a time; a concurrent call returns
    /// `Timeout`.
    pub fn next_request(&self, timeout: Duration) -> Next {
        self.queue.busy.store(false, Ordering::Relaxed);
        let Ok(guard) = self.runtime.try_lock() else {
            return Next::Timeout;
        };
        let Some(rt) = guard.as_ref() else {
            return Next::Stopped;
        };
        // A request already waiting is handed over without entering the event loop, up to `batch`
        // in a row.
        let streak = self.streak.load(Ordering::Relaxed);
        if streak < self.batch {
            if let Some(r) = self.queue.pop() {
                self.streak.store(streak + 1, Ordering::Relaxed);
                self.queue.busy.store(true, Ordering::Relaxed);
                return Next::Request(r);
            }
        }
        let next = rt.block_on(self.run(timeout));
        if matches!(next, Next::Request(_)) {
            self.streak.store(1, Ordering::Relaxed);
            self.queue.busy.store(true, Ordering::Relaxed);
            // Requests left behind while this worker is busy: an idle one may take them.
            if let Some(steal) = &self.queue.steal {
                if self.queue.ready.lock().is_ok_and(|q| !q.is_empty()) {
                    steal.notify_one();
                }
            }
        }
        next
    }

    async fn run(&self, timeout: Duration) -> Next {
        // A waiting request must not go out before the replies answered since the loop last ran:
        // let the connection tasks run first. With nothing waiting they run anyway while we wait.
        if self.queue.ready.lock().is_ok_and(|q| !q.is_empty()) {
            tokio::task::yield_now().await;
        }
        let mut spins = 0;
        // Made only if we have to wait: most calls under load never need it.
        let mut deadline: Option<Pin<Box<tokio::time::Sleep>>> = None;
        loop {
            if let Some(r) = self.queue.pop() {
                return Next::Request(r);
            }
            if self.stopped.load(Ordering::Acquire) {
                return Next::Stopped;
            }
            self.start_incoming();
            if let Some(r) = self.steal() {
                return Next::Request(r);
            }
            if spins < self.spin {
                spins += 1;
                tokio::task::yield_now().await;
                continue;
            }
            let sleep = deadline.get_or_insert_with(|| Box::pin(tokio::time::sleep(timeout)));
            match &self.queue.steal {
                None => tokio::select! {
                    _ = self.queue.wake.notified() => {}
                    _ = sleep.as_mut() => return Next::Timeout,
                },
                Some(steal) => tokio::select! {
                    _ = self.queue.wake.notified() => {}
                    _ = steal.notified() => {}
                    _ = sleep.as_mut() => return Next::Timeout,
                },
            }
        }
    }

    /// Runs this lane's event loop without taking a request, until a request of this lane is
    /// answered (or `timeout` passes). For a worker that hands requests to other threads and has
    /// reached its limit of requests in flight: the answers still get written, while new requests
    /// wait in the queue. Returns false once the server has stopped.
    pub fn drive(&self, timeout: Duration) -> bool {
        self.queue.busy.store(false, Ordering::Relaxed);
        let Ok(guard) = self.runtime.try_lock() else {
            return !self.stopped.load(Ordering::Acquire);
        };
        let Some(rt) = guard.as_ref() else {
            return false;
        };
        rt.block_on(async {
            tokio::task::yield_now().await;
            let _ = tokio::time::timeout(timeout, self.queue.replied.notified()).await;
        });
        !self.stopped.load(Ordering::Acquire)
    }

    /// Takes a request waiting on a lane whose worker is busy, if work stealing is on.
    fn steal(&self) -> Option<ServerRequest> {
        let n = self.all.len();
        for k in 1..n {
            let other = &self.all[(self.index + k) % n];
            if other.busy.load(Ordering::Relaxed) {
                if let Some(r) = other.pop() {
                    self.queue.stats.stolen.fetch_add(1, Ordering::Relaxed);
                    return Some(r);
                }
            }
        }
        None
    }

    fn start_incoming(&self) {
        let accepted = match self.queue.incoming.lock() {
            Ok(mut v) if !v.is_empty() => std::mem::take(&mut *v),
            _ => return,
        };
        for a in accepted {
            self.spawn_connection(a);
        }
    }

    fn spawn_connection(&self, accepted: Accepted) {
        let Accepted { stream, permit } = accepted;
        let _ = stream.set_nonblocking(true);
        let _ = stream.set_nodelay(true);
        let addrs = Addrs {
            peer: stream.peer_addr().ok(),
            local: stream.local_addr().ok(),
        };
        let Ok(stream) = tokio::net::TcpStream::from_std(stream) else {
            return;
        };
        let queue = Arc::clone(&self.queue);
        let tls = self.tls.clone();
        let max_body = self.max_body;
        let shared = Arc::clone(&self.shared);
        tokio::spawn(async move {
            // Held until the connection is gone.
            let _permit = permit;
            match tls {
                None => serve(stream, queue, max_body, addrs, shared).await,
                Some(acceptor) => {
                    // A client that never finishes the handshake must not hold a task forever.
                    let handshake = tokio::time::timeout(
                        shared.limits.tls_handshake_timeout,
                        acceptor.accept(stream),
                    );
                    if let Ok(Ok(tls)) = handshake.await {
                        serve(tls, queue, max_body, addrs, shared).await;
                    }
                }
            }
        });
    }
}

/// What a connection over the limit gets before it is closed.
const OVERLOADED: &[u8] =
    b"HTTP/1.1 503 Service Unavailable\r\nretry-after: 1\r\ncontent-length: 0\r\nconnection: close\r\n\r\n";

pub struct NativePathServer {
    lanes: Vec<Lane>,
    stats: Arc<EngineStats>,
    local_addr: SocketAddr,
    stopped: Arc<AtomicBool>,
    steal: Option<Arc<Notify>>,
    acceptor: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl NativePathServer {
    /// Binds and starts accepting. Returns once the socket is listening.
    pub fn start(config: ServerConfig) -> std::io::Result<Self> {
        let tls = config.tls.as_ref().map(tls_acceptor).transpose()?;
        let listener = std::net::TcpListener::bind(config.addr)?;
        let local_addr = listener.local_addr()?;
        let stopped = Arc::new(AtomicBool::new(false));
        let stats = Arc::new(EngineStats::default());
        let n = config.lanes.max(1);
        let steal = (config.work_stealing && n > 1).then(|| Arc::new(Notify::new()));
        let queues: Vec<Arc<LaneQueue>> = (0..n)
            .map(|_| {
                Arc::new(LaneQueue::new(
                    Arc::clone(&stats),
                    config.max_queued,
                    steal.clone(),
                ))
            })
            .collect();
        let shared = Arc::new(Shared {
            limits: config.limits.clone(),
            connections: AtomicUsize::new(0),
            inflight_body: AtomicUsize::new(0),
        });
        let mut lanes = Vec::new();
        for (index, queue) in queues.iter().enumerate() {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()?;
            lanes.push(Lane {
                runtime: Mutex::new(Some(runtime)),
                queue: Arc::clone(queue),
                all: if steal.is_some() { queues.clone() } else { Vec::new() },
                index,
                stopped: Arc::clone(&stopped),
                tls: tls.clone(),
                max_body: config.max_body_bytes,
                batch: config.batch.max(1),
                spin: config.spin,
                streak: AtomicUsize::new(0),
                shared: Arc::clone(&shared),
            });
        }
        let stop = Arc::clone(&stopped);
        let acceptor_stats = Arc::clone(&stats);
        let acceptor = std::thread::Builder::new()
            .name("nativepath-acceptor".into())
            .spawn(move || {
                let mut next = 0usize;
                for stream in listener.incoming() {
                    if stop.load(Ordering::Acquire) {
                        return;
                    }
                    let Ok(mut stream) = stream else { continue };
                    let Some(permit) = ConnPermit::try_new(&shared) else {
                        // Over the limit: a fast 503 (it fits in a fresh socket's send buffer, so
                        // the write does not block), then the stream is dropped, which closes it.
                        // Counted first, so whoever reads the 503 also sees it counted.
                        acceptor_stats
                            .shed_connections
                            .fetch_add(1, Ordering::Relaxed);
                        use std::io::Write;
                        let _ = stream.write_all(OVERLOADED);
                        let _ = stream.shutdown(std::net::Shutdown::Write);
                        continue;
                    };
                    // Round-robin; the lane's worker wakes on its signal.
                    let queue = &queues[next % queues.len()];
                    if let Ok(mut v) = queue.incoming.lock() {
                        v.push(Accepted { stream, permit });
                    }
                    queue.wake.notify_one();
                    next = next.wrapping_add(1);
                }
            })?;
        Ok(Self {
            lanes,
            stats,
            local_addr,
            stopped,
            steal,
            acceptor: Mutex::new(Some(acceptor)),
        })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    pub fn lanes(&self) -> &[Lane] {
        &self.lanes
    }

    /// Engine timings summed over all lanes since start.
    pub fn stats(&self) -> &EngineStats {
        &self.stats
    }

    pub fn lane(&self, index: usize) -> &Lane {
        &self.lanes[index]
    }

    /// Stops accepting, closes connections and wakes waiting workers. Requests already taken by a
    /// worker can still be answered, but their connections are gone.
    pub fn stop(&self) {
        if self.stopped.swap(true, Ordering::AcqRel) {
            return;
        }
        // Unblock the acceptor's accept() with a throwaway connection, then let it exit.
        let _ = std::net::TcpStream::connect(self.local_addr);
        if let Some(h) = self.acceptor.lock().ok().and_then(|mut a| a.take()) {
            let _ = h.join();
        }
        if let Some(steal) = &self.steal {
            steal.notify_waiters();
        }
        for lane in &self.lanes {
            lane.queue.wake.notify_one();
            lane.queue.replied.notify_one();
            // Waits for the lane's current driver (if any) to return, then drops its connections.
            if let Ok(mut rt) = lane.runtime.lock() {
                if let Some(rt) = rt.take() {
                    rt.shutdown_timeout(Duration::from_secs(1));
                }
            }
            // Requests never taken (each holds its lane's queue, so they must go explicitly; their
            // buffers are wiped on drop) and connections never started.
            if let Ok(mut q) = lane.queue.ready.lock() {
                q.clear();
            }
            if let Ok(mut v) = lane.queue.incoming.lock() {
                v.clear();
            }
        }
    }
}

impl Drop for NativePathServer {
    fn drop(&mut self) {
        self.stop();
    }
}

/// A connection's addresses, copied onto each of its requests.
#[derive(Clone, Copy)]
struct Addrs {
    peer: Option<SocketAddr>,
    local: Option<SocketAddr>,
}

const IDLE: u8 = 0;
const HEAD: u8 = 1;
const BUSY: u8 = 2;

/// What a connection is doing, for its idle and header-read deadlines. hyper's own
/// `header_read_timeout` also runs while a keep-alive connection waits, so it cannot tell the two
/// apart; this does, from the bytes passing through [`Watched`] and the handler's entry and exit.
struct Activity {
    /// `IDLE` (waiting for a request, or writing a response), `HEAD` (head bytes arrived, not yet
    /// parsed) or `BUSY` (in the handler, which bounds the body read itself).
    phase: AtomicU8,
    /// When the phase began, or the last write progress while `IDLE`; nanoseconds since `epoch`.
    since: AtomicU64,
    epoch: Instant,
    /// Woken when a head starts, whose deadline is shorter than the idle one.
    head_started: Notify,
}

impl Activity {
    fn new() -> Self {
        Self {
            phase: AtomicU8::new(IDLE),
            since: AtomicU64::new(0),
            epoch: Instant::now(),
            head_started: Notify::new(),
        }
    }

    fn enter(&self, phase: u8) {
        self.since
            .store(self.epoch.elapsed().as_nanos() as u64, Ordering::Relaxed);
        self.phase.store(phase, Ordering::Release);
    }

    /// When the connection must be closed unless the phase changes; `None` while `BUSY`.
    fn deadline(&self, limits: &Limits) -> Option<Instant> {
        let limit = match self.phase.load(Ordering::Acquire) {
            IDLE => limits.idle_timeout,
            HEAD => limits.header_read_timeout,
            _ => return None,
        };
        let since = self.epoch + Duration::from_nanos(self.since.load(Ordering::Relaxed));
        Some(since + limit)
    }
}

/// The connection's stream, reporting reads and writes to its [`Activity`].
struct Watched<S> {
    io: S,
    activity: Arc<Activity>,
}

impl<S: AsyncRead + Unpin> AsyncRead for Watched<S> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let before = buf.filled().len();
        let r = Pin::new(&mut self.io).poll_read(cx, buf);
        if buf.filled().len() > before && self.activity.phase.load(Ordering::Acquire) == IDLE {
            self.activity.enter(HEAD);
            self.activity.head_started.notify_one();
        }
        r
    }
}

impl<S: AsyncWrite + Unpin> Watched<S> {
    fn wrote(&self, r: &Poll<std::io::Result<usize>>) {
        // A response being written counts as progress, so a slow but live download is not cut off.
        if matches!(r, Poll::Ready(Ok(n)) if *n > 0)
            && self.activity.phase.load(Ordering::Acquire) == IDLE
        {
            self.activity.enter(IDLE);
        }
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for Watched<S> {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let r = Pin::new(&mut self.io).poll_write(cx, buf);
        self.wrote(&r);
        r
    }

    fn poll_write_vectored(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[std::io::IoSlice<'_>],
    ) -> Poll<std::io::Result<usize>> {
        let r = Pin::new(&mut self.io).poll_write_vectored(cx, bufs);
        self.wrote(&r);
        r
    }

    fn is_write_vectored(&self) -> bool {
        self.io.is_write_vectored()
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.io).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.io).poll_shutdown(cx)
    }
}

/// A response body that marks the connection idle once a streamed body has ended (or is dropped).
struct Tracked {
    body: EngineBody,
    activity: Option<Arc<Activity>>,
}

impl Tracked {
    fn done(&mut self) {
        if let Some(a) = self.activity.take() {
            a.enter(IDLE);
        }
    }
}

impl Body for Tracked {
    type Data = Bytes;
    type Error = <EngineBody as Body>::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, Self::Error>>> {
        let r = Pin::new(&mut self.body).poll_frame(cx);
        if matches!(r, Poll::Ready(None)) {
            self.done();
        }
        r
    }

    fn is_end_stream(&self) -> bool {
        self.body.is_end_stream()
    }

    fn size_hint(&self) -> SizeHint {
        self.body.size_hint()
    }
}

impl Drop for Tracked {
    fn drop(&mut self) {
        self.done();
    }
}

async fn serve<S>(io: S, queue: Arc<LaneQueue>, max_body: usize, addrs: Addrs, shared: Arc<Shared>)
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let activity = Arc::new(Activity::new());
    let io = Watched {
        io,
        activity: Arc::clone(&activity),
    };
    let service = {
        let activity = Arc::clone(&activity);
        let shared = Arc::clone(&shared);
        service_fn(move |req| {
            activity.enter(BUSY);
            let activity = Arc::clone(&activity);
            let handled = handle(
                req,
                Arc::clone(&queue),
                max_body,
                addrs,
                Arc::clone(&shared),
            );
            async move {
                let r = handled.await;
                // A streamed body is paced by the worker: the connection stays busy until it ends.
                let streaming = matches!(&r, Ok(r) if matches!(r.body(), EngineBody::Stream(_)));
                if !streaming {
                    activity.enter(IDLE);
                }
                r.map(|r| {
                    r.map(|body| Tracked {
                        body,
                        activity: streaming.then_some(activity),
                    })
                })
            }
        })
    };
    let conn = http1::Builder::new()
        .max_header_size(shared.limits.max_header_bytes)
        .serve_connection(TokioIo::new(io), service);
    tokio::pin!(conn);
    // Dropping `conn` closes the connection.
    loop {
        let deadline = activity.deadline(&shared.limits);
        // While busy, look again after an idle period (the phase can only have moved on).
        let wake = deadline.unwrap_or_else(|| Instant::now() + shared.limits.idle_timeout);
        tokio::select! {
            _ = &mut conn => return,
            _ = activity.head_started.notified() => {}
            _ = tokio::time::sleep_until(wake.into()) => {
                if activity.deadline(&shared.limits).is_some_and(|d| d <= Instant::now()) {
                    return;
                }
            }
        }
    }
}

fn plain(status: StatusCode) -> Response<EngineBody> {
    let mut r = Response::new(EngineBody::Full(Full::new(Bytes::new())));
    *r.status_mut() = status;
    r
}

/// 503 for a request its lane has no room for; the client may retry after a second.
fn shed(queue: &LaneQueue) -> Response<EngineBody> {
    queue.stats.shed_requests.fetch_add(1, Ordering::Relaxed);
    let mut r = plain(StatusCode::SERVICE_UNAVAILABLE);
    r.headers_mut()
        .insert(hyper::header::RETRY_AFTER, HeaderValue::from_static("1"));
    r
}

/// An error response after which the connection is closed (the rest of the body is not read).
fn closing(status: StatusCode) -> Response<EngineBody> {
    let mut r = plain(status);
    r.headers_mut()
        .insert(CONNECTION, HeaderValue::from_static("close"));
    r
}

/// Reads the whole body into `buf` within the size limit and the shared body budget. On error
/// the caller wipes `buf`.
async fn read_body(
    body: &mut Incoming,
    buf: &mut Vec<u8>,
    budget: &mut BodyBudget,
    max_body: usize,
) -> Result<(), Response<EngineBody>> {
    while let Some(frame) = body.frame().await {
        let frame = frame.map_err(|_| plain(StatusCode::BAD_REQUEST))?;
        if let Some(data) = frame.data_ref() {
            let total = buf.len() + data.len();
            if total > max_body {
                return Err(plain(StatusCode::PAYLOAD_TOO_LARGE));
            }
            if !budget.reserve(total) {
                return Err(closing(StatusCode::SERVICE_UNAVAILABLE));
            }
            buf.extend_from_slice(data);
        }
    }
    Ok(())
}

async fn handle(
    req: Request<Incoming>,
    queue: Arc<LaneQueue>,
    max_body: usize,
    addrs: Addrs,
    shared: Arc<Shared>,
) -> Result<Response<EngineBody>, Infallible> {
    let entered = Instant::now();
    // Shed before reading the body: an overloaded lane should spend as little as possible here.
    if queue.full(queue.ready.lock().map(|q| q.len()).unwrap_or(0)) {
        return Ok(shed(&queue));
    }
    let (parts, mut body) = req.into_parts();

    let declared = parts
        .headers
        .get(CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<usize>().ok());
    if declared.is_some_and(|n| n > max_body) {
        return Ok(plain(StatusCode::PAYLOAD_TOO_LARGE));
    }
    let mut budget = BodyBudget {
        shared: Some(Arc::clone(&shared)),
        held: 0,
    };
    // Reserve a declared length before allocating it.
    if !budget.reserve(declared.unwrap_or(0)) {
        return Ok(closing(StatusCode::SERVICE_UNAVAILABLE));
    }
    // One exact-size allocation when the length is known; growth (and its wiped copies) otherwise.
    let mut buf: Vec<u8> = Vec::with_capacity(declared.unwrap_or(0));
    let read = tokio::time::timeout(
        shared.limits.body_read_timeout,
        read_body(&mut body, &mut buf, &mut budget, max_body),
    )
    .await;
    let rejected = match read {
        Ok(Ok(())) => None,
        Ok(Err(response)) => Some(response),
        Err(_) => Some(closing(StatusCode::REQUEST_TIMEOUT)),
    };
    if let Some(response) = rejected {
        wipe_vec(&mut buf);
        return Ok(response);
    }

    let mut packed = Vec::new();
    for (name, value) in parts.headers.iter() {
        packed.extend_from_slice(name.as_str().as_bytes());
        packed.push(0);
        packed.extend_from_slice(value.as_bytes());
        packed.push(0);
    }

    let (reply_tx, reply_rx) = oneshot::channel();
    let now = Instant::now();
    let request = ServerRequest {
        method: parts.method.as_str().to_string(),
        target: parts
            .uri
            .path_and_query()
            .map(|p| p.as_str())
            .unwrap_or("/")
            .to_string(),
        headers: SecretBuffer::new(packed),
        body: SecretBuffer::new(buf),
        reply: Some(reply_tx),
        lane: Arc::clone(&queue),
        queued_at: now,
        taken_at: now,
        peer: addrs.peer,
        local: addrs.local,
        _budget: budget,
    };
    EngineStats::add(&queue.stats.read_ns, &queue.stats.hist[0], entered);
    match queue.ready.lock() {
        // Checked again: the queue may have filled while the body was read. The request (and its
        // wiped buffers) is dropped.
        Ok(ready) if queue.full(ready.len()) => {
            drop(ready);
            drop(request);
            return Ok(shed(&queue));
        }
        Ok(mut ready) => ready.push_back(request),
        Err(_) => return Ok(plain(StatusCode::SERVICE_UNAVAILABLE)),
    }
    queue.wake.notify_one();
    // The owning worker is busy: let an idle one take it.
    if let Some(steal) = &queue.steal {
        if queue.busy.load(Ordering::Relaxed) {
            steal.notify_one();
        }
    }
    match reply_rx.await {
        Ok(reply) => {
            EngineStats::add(&queue.stats.reply_ns, &queue.stats.hist[4], reply.answered_at);
            queue.stats.requests.fetch_add(1, Ordering::Relaxed);
            let mut r = Response::new(reply.body);
            *r.status_mut() =
                StatusCode::from_u16(reply.status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
            let h = r.headers_mut();
            for (name, value) in reply.headers {
                h.append(name, value);
            }
            Ok(r)
        }
        // The worker dropped the request without answering.
        Err(_) => Ok(plain(StatusCode::INTERNAL_SERVER_ERROR)),
    }
}
