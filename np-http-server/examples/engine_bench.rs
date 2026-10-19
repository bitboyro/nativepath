//! The engine with no JVM: one Rust worker per lane answers every request with "hello". Measures
//! the ceiling of the lane design (the worker drives its lane's event loop) without FFI or Java.
//!
//!     cargo run --release -p np-http-server --example engine_bench -- <port> [lanes=cores] [cert.pem key.pem]
//!
//! `BENCH_BIND` sets the address to listen on (default 127.0.0.1). Engine settings come from the
//! environment, named as the Spring properties are:
//! `NATIVEPATH_SERVER_BATCH`, `_SPIN`, `_WORK_STEALING` (true/false), `_MAX_QUEUED`,
//! `_MAX_CONNECTIONS`. `ENGINE_WORK_US=<n>` makes each answer burn n µs of CPU first (a handler
//! that does some work), so batching and stealing have something to act on.

use np_memory::alloc::ZeroizingAllocator;
use np_http_server::{NativePathServer, Next, ServerConfig};
use std::sync::Arc;
use std::time::Duration;

#[global_allocator]
static GLOBAL: ZeroizingAllocator<std::alloc::System> = ZeroizingAllocator(std::alloc::System);

fn setting(name: &str, default: usize) -> usize {
    std::env::var(format!("NATIVEPATH_SERVER_{name}"))
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

/// Burns `us` microseconds of CPU on the calling thread.
fn work(us: u64) {
    if us == 0 {
        return;
    }
    let until = std::time::Instant::now() + Duration::from_micros(us);
    while std::time::Instant::now() < until {
        std::hint::spin_loop();
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let port: u16 = args.first().and_then(|s| s.parse().ok()).unwrap_or(18090);
    let defaults = ServerConfig::default();
    let lanes: usize = args
        .get(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(defaults.lanes);
    let server = Arc::new(
        NativePathServer::start(ServerConfig {
            // BENCH_BIND=0.0.0.0 to be reached from another machine (as ServerBenchApp).
            addr: std::net::SocketAddr::new(
                std::env::var("BENCH_BIND")
                    .ok()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(std::net::Ipv4Addr::LOCALHOST.into()),
                port,
            ),
            lanes,
            tls: match (args.get(2), args.get(3)) {
                (Some(cert), Some(key)) => Some(np_http_server::TlsFiles {
                    cert_chain: cert.into(),
                    private_key: key.into(),
                }),
                _ => None,
            },
            batch: setting("BATCH", defaults.batch),
            spin: setting("SPIN", defaults.spin),
            work_stealing: std::env::var("NATIVEPATH_SERVER_WORK_STEALING").is_ok_and(|v| v == "true"),
            max_queued: setting("MAX_QUEUED", defaults.max_queued),
            limits: np_http_server::Limits {
                max_connections: setting("MAX_CONNECTIONS", defaults.limits.max_connections),
                ..defaults.limits.clone()
            },
            ..defaults
        })
        .unwrap(),
    );
    let work_us: u64 = std::env::var("ENGINE_WORK_US")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    println!("READY {}", server.local_addr());
    let handles: Vec<_> = (0..lanes)
        .map(|i| {
            let s = Arc::clone(&server);
            std::thread::spawn(move || loop {
                match s.lane(i).next_request(Duration::from_millis(200)) {
                    Next::Request(r) if r.target == "/__stats" => {
                        let e = s.stats().snapshot();
                        let body = format!(
                            "{{\"alloc_bytes\":0,\"engine\":{e:?}}}"
                        );
                        let _ = r.respond(200, b"content-type\0application/json\0", body.as_bytes());
                    }
                    Next::Request(r) => {
                        work(work_us);
                        let _ = r.respond(200, b"content-type\0text/plain\0", b"hello");
                    }
                    Next::Timeout => continue,
                    Next::Stopped => return,
                }
            })
        })
        .collect();
    for h in handles {
        let _ = h.join();
    }
}
