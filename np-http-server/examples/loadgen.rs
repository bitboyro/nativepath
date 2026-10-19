//! HTTP load generator: N connections for a fixed duration; prints throughput and latency
//! percentiles as one JSON line.
//!
//! Closed loop (default): each connection sends its next request as soon as the previous response
//! is read, so a slow server is offered less load: it shows the best throughput, not what users
//! see under a given load.
//!
//! Open loop (`LOADGEN_RATE=<requests per second>`, all connections together): requests are due on
//! a fixed schedule whether or not the server keeps up, and latency counts from when a request was
//! due, not from when it was sent ("coordinated omission" corrected, as wrk2 does). Past what the
//! server can do, latency then grows with every second of the run instead of looking flat. Needs
//! enough connections to have the rate's requests in flight (rate x latency).
//!
//! 503 answers (overload shedding) are counted as `shed`, apart from other errors.
//!
//! Progress and verification (all optional, all through the environment):
//!
//! - `LOADGEN_EVENTS=<file>` (or `-` for stderr, each line prefixed `NPEV `, which is how a run on
//!   another machine streams back through ssh): once per second, appends one JSON line (`"type":"tick"`: requests,
//!   latency percentiles, errors, shed, MB/s of that second), and at the end one `"type":"result"`
//!   line. `LOADGEN_TAG='"exp":"throughput","config":"np"'` is copied into every line, so a
//!   dashboard can tell the lines apart.
//! - `LOADGEN_EXPECT_STATUS=<code>`, `LOADGEN_EXPECT_LEN=<bytes>`, `LOADGEN_EXPECT_FNV=<hex>` (FNV-1a
//!   64 of the body) and `LOADGEN_EXPECT_ECHO=1` (the body must equal the posted body): every
//!   response is checked, and one that differs is a mismatch, counted apart from errors.
//!   `LOADGEN_STOP_ON_MISMATCH=1` ends the run at the first one.
//! - Runs of more than 120 s keep latency in a histogram (1% buckets) instead of a list, so memory
//!   stays flat for a soak.
//!
//!     cargo run --release -p np-http-server --example loadgen -- \
//!         <url> [connections=32] [seconds=10] [post_body_bytes=0]
//!
//! With post_body_bytes > 0 every request is a POST carrying that many bytes; `@path` posts the
//! file's contents instead (as application/json when the name ends in .json).
//!
//! Environment: `LOADGEN_CA=<pem>` trusts that certificate for https URLs (verification stays on);
//! `LOADGEN_NEW_CONN=1` opens a new connection for every request (a full TLS handshake each time).
//! Those requests carry `Connection: close`, so the server closes first and holds TIME_WAIT; if the
//! client closed first, its ephemeral ports (about 16k on macOS, held 30 s each) would run out
//! within seconds and the run would measure port exhaustion instead of the server.

use std::io::Write;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

fn new_conn() -> bool {
    std::env::var("LOADGEN_NEW_CONN").is_ok_and(|v| v == "1")
}

fn client_builder() -> reqwest::ClientBuilder {
    let new_conn = new_conn();
    // One keep-alive connection per worker, or none (every request opens its own).
    // A request that takes longer than this counts as an error instead of hanging the run.
    let mut b = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(30))
        .pool_max_idle_per_host(if new_conn { 0 } else { 1 });
    if let Ok(ca) = std::env::var("LOADGEN_CA") {
        let pem = std::fs::read(&ca).expect("read LOADGEN_CA");
        b = b.add_root_certificate(
            reqwest::Certificate::from_pem(&pem).expect("LOADGEN_CA is not a PEM certificate"),
        );
    }
    b
}

/// FNV-1a, 64 bit: a cheap fingerprint to check a body against, not a security hash.
fn fnv(b: &[u8]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    for &x in b {
        h ^= x as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// What every response must look like; all unset means no checking.
struct Expect {
    status: Option<u16>,
    len: Option<usize>,
    fnv: Option<u64>,
    echo: bool,
    stop_on_mismatch: bool,
}

impl Expect {
    fn from_env() -> Expect {
        let var = |k: &str| std::env::var(k).ok();
        Expect {
            status: var("LOADGEN_EXPECT_STATUS").and_then(|v| v.parse().ok()),
            len: var("LOADGEN_EXPECT_LEN").and_then(|v| v.parse().ok()),
            fnv: var("LOADGEN_EXPECT_FNV").and_then(|v| u64::from_str_radix(v.trim_start_matches("0x"), 16).ok()),
            echo: var("LOADGEN_EXPECT_ECHO").is_some_and(|v| v == "1"),
            stop_on_mismatch: var("LOADGEN_STOP_ON_MISMATCH").is_some_and(|v| v == "1"),
        }
    }
    fn active(&self) -> bool {
        self.status.is_some() || self.len.is_some() || self.fnv.is_some() || self.echo
    }
    /// None when the response is as expected, else what differed.
    fn check(&self, status: u16, body: &[u8], posted: &[u8]) -> Option<String> {
        let mut why = Vec::new();
        if let Some(s) = self.status {
            if s != status {
                why.push(format!("status {status}, expected {s}"));
            }
        }
        if let Some(l) = self.len {
            if l != body.len() {
                why.push(format!("{} bytes, expected {l}", body.len()));
            }
        }
        if let Some(h) = self.fnv {
            let got = fnv(body);
            if got != h {
                why.push(format!("fnv {got:016x}, expected {h:016x}"));
            }
        }
        if self.echo && body != posted {
            why.push(format!("body is not the {} bytes posted", posted.len()));
        }
        (!why.is_empty()).then(|| why.join("; "))
    }
}

/// What the workers share: the latencies since the last tick, and running totals.
#[derive(Default)]
struct Shared {
    window: Mutex<Vec<u64>>,
    errors: AtomicU64,
    shed: AtomicU64,
    bytes: AtomicU64,
    verified: AtomicU64,
    mismatches: AtomicU64,
    stop: AtomicBool,
    done: AtomicBool,
    first_mismatch: Mutex<Option<String>>,
}

/// Latency histogram with 1% buckets, for runs too long to keep every sample.
struct Hist(Vec<u64>);
impl Hist {
    const G: f64 = 1.01;
    fn new() -> Hist {
        Hist(vec![0; 2800])
    }
    fn add(&mut self, ns: u64) {
        let i = ((ns.max(1) as f64).ln() / Self::G.ln()) as usize;
        let n = self.0.len();
        self.0[i.min(n - 1)] += 1;
    }
    fn count(&self) -> u64 {
        self.0.iter().sum()
    }
    fn pct(&self, p: f64) -> f64 {
        let total = self.count();
        let want = ((total as f64 * p) as u64).min(total.saturating_sub(1));
        let mut seen = 0;
        for (i, c) in self.0.iter().enumerate() {
            seen += c;
            if seen > want {
                return Self::G.powi(i as i32);
            }
        }
        0.0
    }
}

fn now_ms() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis())
}

fn esc(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// Appends one JSON line (small enough to be written whole even with other writers on the file).
fn emit(sink: &Option<Sink>, tag: &str, kind: &str, fields: &str) {
    if let Some(sink) = sink {
        let tag = if tag.is_empty() { String::new() } else { format!("{tag},") };
        let sep = if fields.is_empty() { "" } else { "," };
        let line = format!("{{\"t\":{},\"type\":\"{kind}\",{tag}\"src\":\"loadgen\"{sep}{fields}}}\n", now_ms());
        match sink {
            Sink::File(f) => {
                let _ = f.lock().unwrap().write_all(line.as_bytes());
            }
            Sink::Stderr => eprint!("NPEV {line}"),
        }
    }
}

/// Where events go: a file, or stderr with a prefix.
enum Sink {
    File(Mutex<std::fs::File>),
    Stderr,
}

fn pct_sorted(v: &[u64], p: f64) -> f64 {
    v.get(((v.len() as f64 * p) as usize).min(v.len().saturating_sub(1))).copied().unwrap_or(0) as f64 / 1000.0
}

#[tokio::main(flavor = "multi_thread")]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let url = args
        .first()
        .expect("usage: loadgen <url> [connections] [seconds] [post_body_bytes]")
        .clone();
    let connections: usize = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(32);
    let seconds: u64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(10);
    let body_arg = args.get(3).cloned().unwrap_or_default();
    let (body, content_type) = match body_arg.strip_prefix('@') {
        Some(path) => (
            std::fs::read(path).expect("read body file"),
            if path.ends_with(".json") {
                "application/json"
            } else {
                "application/octet-stream"
            },
        ),
        None => (
            vec![b'x'; body_arg.parse().unwrap_or(0)],
            "application/octet-stream",
        ),
    };
    let body_len = body.len();
    let body = bytes::Bytes::from(body);
    // Open loop: each connection has its share of the rate, staggered so they do not send together.
    let rate: f64 = std::env::var("LOADGEN_RATE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0.0);
    let interval = (rate > 0.0).then(|| Duration::from_secs_f64(connections as f64 / rate));
    let expect = Arc::new(Expect::from_env());
    let tag = std::env::var("LOADGEN_TAG").unwrap_or_default();
    let events: Arc<Option<Sink>> = Arc::new(std::env::var("LOADGEN_EVENTS").ok().and_then(|p| {
        if p == "-" {
            Some(Sink::Stderr)
        } else {
            std::fs::OpenOptions::new().create(true).append(true).open(p).ok().map(|f| Sink::File(Mutex::new(f)))
        }
    }));
    let exact = seconds <= 120;
    let shared = Arc::new(Shared::default());

    // One client per connection keeps exactly one keep-alive connection per worker.
    let warmup = Duration::from_secs(2);
    let measure = Duration::from_secs(seconds);
    let start = Instant::now();

    // Once a second: the latency of that second and the running totals as one event. Past 120 s it
    // also builds the histogram the final percentiles come from.
    let reporter = {
        let (shared, events, tag) = (shared.clone(), events.clone(), tag.clone());
        tokio::spawn(async move {
            let mut hist = Hist::new();
            let (mut last_err, mut last_shed, mut last_bytes, mut last_ver, mut last_mm) = (0u64, 0u64, 0u64, 0u64, 0u64);
            let mut sec = 0u64;
            loop {
                tokio::time::sleep_until((start + Duration::from_secs(sec + 1)).into()).await;
                sec += 1;
                let mut w = std::mem::take(&mut *shared.window.lock().unwrap());
                w.sort_unstable();
                if !exact && sec > warmup.as_secs() && sec <= warmup.as_secs() + seconds {
                    w.iter().for_each(|&n| hist.add(n));
                }
                let (e, s, b, v, m) = (
                    shared.errors.load(Relaxed),
                    shared.shed.load(Relaxed),
                    shared.bytes.load(Relaxed),
                    shared.verified.load(Relaxed),
                    shared.mismatches.load(Relaxed),
                );
                let fields = format!(
                    "\"sec\":{sec},\"phase\":\"{}\",\"conns\":{connections},\"target_rps\":{rate:.0},\"rps\":{},\"p50_us\":{:.0},\"p99_us\":{:.0},\"p999_us\":{:.0},\"max_us\":{:.0},\"errors\":{},\"shed\":{},\"mb_s\":{:.2},\"verified\":{},\"mismatches\":{}",
                    if sec <= warmup.as_secs() {
                        "warmup"
                    } else if sec <= warmup.as_secs() + seconds {
                        "measure"
                    } else {
                        "drain" // the partial second after the window: not part of any result
                    },
                    w.len(),
                    pct_sorted(&w, 0.5),
                    pct_sorted(&w, 0.99),
                    pct_sorted(&w, 0.999),
                    w.last().copied().unwrap_or(0) as f64 / 1000.0,
                    e - last_err,
                    s - last_shed,
                    (b - last_bytes) as f64 / 1e6,
                    v - last_ver,
                    m - last_mm
                );
                emit(&events, &tag, "tick", &fields);
                (last_err, last_shed, last_bytes, last_ver, last_mm) = (e, s, b, v, m);
                if shared.done.load(Relaxed) {
                    return hist;
                }
            }
        })
    };

    let mut tasks = Vec::new();
    for k in 0..connections {
        let url = url.clone();
        let body = body.clone();
        let (shared, expect) = (shared.clone(), expect.clone());
        tasks.push(tokio::spawn(async move {
            let client = client_builder().build().unwrap();
            let close = new_conn();
            let mut lat = Vec::new();
            let mut pending: Vec<u64> = Vec::with_capacity(128);
            let mut last_flush = Instant::now();
            let (mut errors, mut bytes, mut shed) = (0u64, 0u64, 0u64);
            let mut first_error: Option<String> = None;
            let mut due = interval.map(|i| start + i.mul_f64(k as f64 / connections as f64));
            let mut prev_done = start;
            loop {
                if shared.stop.load(Relaxed) {
                    break;
                }
                // Open loop: when the previous response came back after this request was due, the
                // server made it late, so latency counts from when it was due. Otherwise it counts
                // from the send: the timer (1 ms resolution) may wake us a little late, and that is
                // not the server's doing.
                let t = match (due, interval) {
                    (Some(d), Some(i)) => {
                        tokio::time::sleep_until(d.into()).await;
                        due = Some(d + i);
                        if prev_done > d {
                            d
                        } else {
                            Instant::now()
                        }
                    }
                    _ => Instant::now(),
                };
                // Stop on the clock, not on the schedule: an open loop that has fallen behind must
                // not run on past the window and serve everything it was offered.
                if t.duration_since(start) >= warmup + measure
                    || Instant::now().duration_since(start) >= warmup + measure
                {
                    break;
                }
                let req = if body_len > 0 {
                    client
                        .post(&url)
                        .header("content-type", content_type)
                        .body(body.clone())
                } else {
                    client.get(&url)
                };
                let req = if close { req.header("connection", "close") } else { req };
                let mut mismatch: Option<String> = None;
                let outcome = match req.send().await {
                    Ok(r) => {
                        let status = r.status().as_u16();
                        let wanted = match expect.status {
                            Some(s) => s == status,
                            None => r.status().is_success(),
                        };
                        if wanted {
                            match r.bytes().await {
                                Ok(b) => {
                                    bytes += b.len() as u64;
                                    shared.bytes.fetch_add(b.len() as u64, Relaxed);
                                    if expect.active() {
                                        mismatch = expect.check(status, &b, &body);
                                    }
                                    Ok(())
                                }
                                Err(e) => Err(format!("reading body: {e}")),
                            }
                        } else if status == 503 {
                            let _ = r.bytes().await;
                            Err(String::from("503"))
                        } else {
                            let _ = r.bytes().await;
                            if expect.active() {
                                mismatch = Some(format!("status {status}, expected {:?}", expect.status));
                            }
                            Err(format!("status {status}"))
                        }
                    }
                    Err(e) => Err(format!("{e:?}")),
                };
                prev_done = Instant::now();
                let ok = outcome.is_ok();
                let was_shed = matches!(&outcome, Err(e) if e == "503");
                if let (Err(e), None, false) = (&outcome, &first_error, was_shed) {
                    first_error = Some(e.clone());
                }
                if expect.active() {
                    match &mismatch {
                        Some(why) => {
                            shared.mismatches.fetch_add(1, Relaxed);
                            shared.first_mismatch.lock().unwrap().get_or_insert_with(|| why.clone());
                            if expect.stop_on_mismatch {
                                shared.stop.store(true, Relaxed);
                            }
                        }
                        None if ok => {
                            shared.verified.fetch_add(1, Relaxed);
                        }
                        None => {}
                    }
                }
                if ok {
                    pending.push(t.elapsed().as_nanos() as u64);
                } else if was_shed {
                    shared.shed.fetch_add(1, Relaxed);
                } else {
                    shared.errors.fetch_add(1, Relaxed);
                }
                if pending.len() >= 64 || last_flush.elapsed() >= Duration::from_millis(250) {
                    shared.window.lock().unwrap().extend(pending.iter());
                    pending.clear();
                    last_flush = Instant::now();
                }
                // Counted by when the answer arrived: inside the measured window, whenever the
                // request was due (a backlog must not hide throughput, nor run past the window).
                let done = Instant::now().duration_since(start);
                if done >= warmup && done < warmup + measure {
                    if ok {
                        if exact {
                            lat.push(t.elapsed().as_nanos() as u64);
                        }
                    } else if was_shed {
                        shed += 1;
                    } else {
                        errors += 1;
                    }
                }
            }
            shared.window.lock().unwrap().extend(pending.iter());
            (lat, errors, bytes, first_error, shed)
        }));
    }
    let mut all = Vec::new();
    let mut first_error: Option<String> = None;
    let (mut errors, mut bytes, mut shed) = (0u64, 0u64, 0u64);
    for t in tasks {
        let (l, e, b, err, s) = t.await.unwrap();
        shed += s;
        if let Some(err) = err {
            first_error.get_or_insert(err);
        }
        all.extend(l);
        errors += e;
        bytes += b;
    }
    shared.done.store(true, Relaxed);
    let hist = reporter.await.unwrap();
    if let Some(e) = &first_error {
        // On stderr, so the JSON result on stdout stays clean.
        eprintln!("loadgen: first error: {e}");
    }
    let mismatch = shared.first_mismatch.lock().unwrap().clone();
    if let Some(m) = &mismatch {
        eprintln!("loadgen: first mismatch: {m}");
    }
    all.sort_unstable();
    let (requests, p50, p99, p999) = if exact {
        (all.len() as u64, pct_sorted(&all, 0.50), pct_sorted(&all, 0.99), pct_sorted(&all, 0.999))
    } else {
        (hist.count(), hist.pct(0.50) / 1000.0, hist.pct(0.99) / 1000.0, hist.pct(0.999) / 1000.0)
    };
    let rps = requests as f64 / measure.as_secs_f64();
    let (verified, mismatches) = (shared.verified.load(Relaxed), shared.mismatches.load(Relaxed));
    let fields = format!(
        "\"connections\":{connections},\"seconds\":{seconds},\"post_bytes\":{body_len},\"requests\":{requests},\"errors\":{errors},\"shed\":{shed},\"target_rps\":{rate:.0},\"rps\":{rps:.0},\"p50_us\":{p50:.0},\"p99_us\":{p99:.0},\"p999_us\":{p999:.0},\"mb_per_s\":{:.1},\"verified\":{verified},\"mismatches\":{mismatches}",
        bytes as f64 / 1e6 / (measure.as_secs_f64() + warmup.as_secs_f64())
    );
    println!("{{\"url\":\"{url}\",{fields}}}");
    let extra = match &mismatch {
        Some(m) => format!(",\"first_mismatch\":\"{}\"", esc(m)),
        None => String::new(),
    };
    emit(&events, &tag, "result", &format!("\"url\":\"{}\",{fields}{extra}", esc(&url)));
    if mismatches > 0 && expect.stop_on_mismatch {
        std::process::exit(3);
    }
}
