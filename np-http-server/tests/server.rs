use np_http_server::{NativePathServer, Next, ServerConfig};
use std::sync::Arc;
use std::time::Duration;

/// Starts a server whose workers (one per lane) run `handler` on every request.
fn serve(
    config: ServerConfig,
    handler: fn(np_http_server::ServerRequest),
) -> (Arc<NativePathServer>, String) {
    let server = Arc::new(NativePathServer::start(config).unwrap());
    let base = format!("http://{}", server.local_addr());
    // One worker per lane, each driving its own lane's event loop.
    for i in 0..server.lanes().len() {
        let s = Arc::clone(&server);
        std::thread::spawn(move || loop {
            match s.lane(i).next_request(Duration::from_millis(50)) {
                Next::Request(r) => handler(r),
                Next::Timeout => continue,
                Next::Stopped => return,
            }
        });
    }
    (server, base)
}

fn echo(r: np_http_server::ServerRequest) {
    let mut headers = Vec::new();
    for (k, v) in [
        ("x-method", r.method.clone()),
        ("x-target", r.target.clone()),
    ] {
        headers.extend_from_slice(k.as_bytes());
        headers.push(0);
        headers.extend_from_slice(v.as_bytes());
        headers.push(0);
    }
    // Echo the request's x-token header back so header parsing is covered too.
    let packed = r.headers_packed().to_vec();
    let mut it = packed.split(|b| *b == 0);
    while let (Some(n), Some(v)) = (it.next(), it.next()) {
        if n == b"x-token" {
            headers.extend_from_slice(b"x-token-echo\0");
            headers.extend_from_slice(v);
            headers.push(0);
        }
    }
    let body = r.body().to_vec();
    r.respond(201, &headers, &body).unwrap();
}

#[test]
fn echoes_method_target_headers_and_body() {
    let (server, base) = serve(ServerConfig::default(), echo);
    let client = reqwest::blocking::Client::new();
    let body: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
    let resp = client
        .post(format!("{base}/api/items?id=7"))
        .header("X-Token", "abc")
        .body(body.clone())
        .send()
        .unwrap();
    assert_eq!(resp.status().as_u16(), 201);
    assert_eq!(resp.headers()["x-method"], "POST");
    assert_eq!(resp.headers()["x-target"], "/api/items?id=7");
    assert_eq!(resp.headers()["x-token-echo"], "abc");
    assert_eq!(resp.bytes().unwrap().as_ref(), &body[..]);
    server.stop();
}

#[test]
fn rejects_oversized_bodies_with_413() {
    let config = ServerConfig {
        max_body_bytes: 1000,
        ..ServerConfig::default()
    };
    let (server, base) = serve(config, echo);
    let client = reqwest::blocking::Client::new();
    let resp = client
        .post(format!("{base}/big"))
        .body(vec![1u8; 1001])
        .send()
        .unwrap();
    assert_eq!(resp.status().as_u16(), 413);
    let resp = client
        .post(format!("{base}/ok"))
        .body(vec![1u8; 1000])
        .send()
        .unwrap();
    assert_eq!(resp.status().as_u16(), 201);
    server.stop();
}

#[test]
fn dropped_request_gets_500() {
    let (server, base) = serve(ServerConfig::default(), |r| drop(r));
    let resp = reqwest::blocking::get(format!("{base}/x")).unwrap();
    assert_eq!(resp.status().as_u16(), 500);
    server.stop();
}

#[test]
fn stop_wakes_waiting_workers() {
    let server = NativePathServer::start(ServerConfig::default()).unwrap();
    std::thread::scope(|s| {
        let h = s.spawn(|| {
            matches!(
                server.lane(0).next_request(Duration::from_secs(30)),
                Next::Stopped
            )
        });
        std::thread::sleep(Duration::from_millis(100));
        server.stop();
        assert!(h.join().unwrap());
    });
}

fn fixture(name: &str) -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

fn tls_config() -> ServerConfig {
    ServerConfig {
        tls: Some(np_http_server::TlsFiles {
            cert_chain: fixture("test-cert.pem"),
            private_key: fixture("test-key.pem"),
        }),
        ..ServerConfig::default()
    }
}

#[test]
fn serves_https_with_verified_certificate() {
    let (server, base) = serve(tls_config(), echo);
    let https = base.replace("http://", "https://");
    let ca =
        reqwest::Certificate::from_pem(&std::fs::read(fixture("test-cert.pem")).unwrap()).unwrap();
    let client = reqwest::blocking::Client::builder()
        .add_root_certificate(ca)
        .build()
        .unwrap();
    let resp = client
        .post(format!("{https}/tls?x=1"))
        .body("over tls")
        .send()
        .unwrap();
    assert_eq!(resp.status().as_u16(), 201);
    assert_eq!(resp.headers()["x-target"], "/tls?x=1");
    assert_eq!(resp.text().unwrap(), "over tls");
    // Plain HTTP to the TLS port must not be served.
    assert!(reqwest::blocking::get(format!("{base}/plain")).is_err());
    server.stop();
}

#[test]
fn bad_tls_key_fails_at_start() {
    let mut config = tls_config();
    config.tls.as_mut().unwrap().private_key = fixture("missing-key.pem");
    let err = NativePathServer::start(config).err().expect("start must fail");
    assert!(err.to_string().contains("missing-key.pem"), "{err}");
}

#[test]
fn many_connections_spread_over_lanes() {
    let config = ServerConfig {
        lanes: 4,
        ..ServerConfig::default()
    };
    let (server, base) = serve(config, echo);
    let handles: Vec<_> = (0..16)
        .map(|i| {
            let url = format!("{base}/c{i}");
            std::thread::spawn(move || {
                let client = reqwest::blocking::Client::new();
                for j in 0..20 {
                    let r = client.post(&url).body(format!("{i}-{j}")).send().unwrap();
                    assert_eq!(r.status().as_u16(), 201);
                    assert_eq!(r.text().unwrap(), format!("{i}-{j}"));
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
    server.stop();
}

/// Answers on another thread (as a worker pool would), streaming the body in three chunks:
/// `/chunked` without a length, `/sized` with Content-Length, `/abort` cut off after one chunk.
/// The peer address goes back in `x-peer`.
fn stream_off_lane(r: np_http_server::ServerRequest) {
    std::thread::spawn(move || {
        let chunk = vec![b'z'; 100_000];
        let peer = r.peer.map(|a| a.ip().to_string()).unwrap_or_default();
        let headers = format!("x-peer\0{peer}\0").into_bytes();
        let target = r.target.clone();
        let length = (target == "/sized").then_some(300_000);
        let w = r.respond_streaming(200, &headers, length).unwrap();
        w.write(&chunk).unwrap();
        if target == "/abort" {
            w.abort();
            return;
        }
        std::thread::sleep(Duration::from_millis(20)); // produced over time
        w.write(&chunk).unwrap();
        w.write(&chunk).unwrap();
        w.finish();
    });
}

#[test]
fn streams_responses_from_another_thread() {
    let (server, base) = serve(ServerConfig::default(), stream_off_lane);
    let client = reqwest::blocking::Client::new();
    for path in ["/chunked", "/sized"] {
        let resp = client.get(format!("{base}{path}")).send().unwrap();
        assert_eq!(resp.status().as_u16(), 200);
        assert_eq!(resp.headers()["x-peer"], "127.0.0.1");
        let chunked = resp.headers().get("transfer-encoding").is_some();
        assert_eq!(chunked, path == "/chunked", "{path}");
        let body = resp.bytes().unwrap();
        assert_eq!(body.len(), 300_000, "{path}");
        assert!(body.iter().all(|b| *b == b'z'));
    }
    // An aborted stream must not look like a complete response: it fails before or during the body.
    let aborted = client
        .get(format!("{base}/abort"))
        .send()
        .and_then(|r| r.bytes());
    assert!(aborted.is_err());
    server.stop();
}

/// Many concurrent clients against every combination of the lane settings: every request gets its
/// own answer back.
#[test]
fn batching_spinning_and_stealing_keep_answers_straight() {
    for (batch, spin, work_stealing) in [(8, 0, false), (1, 2, false), (4, 1, true), (1, 0, true)] {
        let config = ServerConfig {
            lanes: 3,
            batch,
            spin,
            work_stealing,
            ..ServerConfig::default()
        };
        let (server, base) = serve(config, echo);
        let handles: Vec<_> = (0..12)
            .map(|i| {
                let url = format!("{base}/c{i}");
                std::thread::spawn(move || {
                    let client = reqwest::blocking::Client::new();
                    for j in 0..30 {
                        let r = client.post(&url).body(format!("{i}-{j}")).send().unwrap();
                        assert_eq!(r.status().as_u16(), 201);
                        assert_eq!(r.headers()["x-target"], format!("/c{i}").as_str());
                        assert_eq!(r.text().unwrap(), format!("{i}-{j}"));
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }
        assert_eq!(server.stats().snapshot()[0], 12 * 30, "{batch} {spin} {work_stealing}");
        server.stop();
    }
}

/// Lane 0's worker holds one request while a second waits behind it; lane 1's idle worker takes
/// (steals) the second. Its reply is still written by lane 0, once lane 0's loop runs again.
#[test]
fn idle_worker_steals_from_a_busy_lane() {
    use std::io::{Read, Write};
    let config = ServerConfig {
        lanes: 2,
        work_stealing: true,
        ..ServerConfig::default()
    };
    let server = Arc::new(NativePathServer::start(config).unwrap());
    let addr = server.local_addr();
    // Lane 1 has a worker; lane 0 is driven by hand below.
    let s = Arc::clone(&server);
    let stealer = std::thread::spawn(move || loop {
        match s.lane(1).next_request(Duration::from_millis(50)) {
            Next::Request(r) => echo(r),
            Next::Timeout => continue,
            Next::Stopped => return,
        }
    });
    // Round-robin: connections a and c go to lane 0, b to lane 1. Both requests on lane 0 are
    // written before its loop first runs, so it parses them together.
    let get = |path: &str| format!("GET {path} HTTP/1.1\r\nhost: t\r\nconnection: close\r\n\r\n");
    let mut a = std::net::TcpStream::connect(addr).unwrap();
    let _b = std::net::TcpStream::connect(addr).unwrap();
    let mut c = std::net::TcpStream::connect(addr).unwrap();
    a.write_all(get("/a").as_bytes()).unwrap();
    c.write_all(get("/c").as_bytes()).unwrap();
    std::thread::sleep(Duration::from_millis(100));
    let first = loop {
        if let Next::Request(r) = server.lane(0).next_request(Duration::from_millis(50)) {
            break r;
        }
    };
    let deadline = std::time::Instant::now() + Duration::from_secs(3);
    while server.stats().snapshot()[8] == 0 {
        assert!(std::time::Instant::now() < deadline, "nothing was stolen");
        std::thread::sleep(Duration::from_millis(10));
    }
    let stolen_path = if first.target == "/a" { "/c" } else { "/a" };
    echo(first);
    // Lane 0's loop writes both replies.
    let s = Arc::clone(&server);
    let driver = std::thread::spawn(move || loop {
        if let Next::Stopped = s.lane(0).next_request(Duration::from_millis(50)) {
            return;
        }
    });
    for (stream, path) in [(&mut a, "/a"), (&mut c, "/c")] {
        let mut answer = String::new();
        stream.read_to_string(&mut answer).unwrap();
        assert!(answer.starts_with("HTTP/1.1 201"), "{path}: {answer}");
        assert!(answer.contains(&format!("x-target: {path}")), "{path}: {answer}");
    }
    assert_eq!(server.stats().snapshot()[8], 1, "{stolen_path} stolen once");
    server.stop();
    stealer.join().unwrap();
    driver.join().unwrap();
}

/// A full queue answers 503 with Retry-After at once instead of letting requests pile up.
#[test]
fn full_queue_sheds_with_503() {
    let config = ServerConfig {
        lanes: 1,
        max_queued: 1,
        ..ServerConfig::default()
    };
    let (server, base) = serve(config, |r| {
        std::thread::sleep(Duration::from_millis(300));
        echo(r)
    });
    let handles: Vec<_> = (0..6)
        .map(|i| {
            let url = format!("{base}/q{i}");
            std::thread::spawn(move || {
                let r = reqwest::blocking::Client::new().get(url).send().unwrap();
                let retry = r.headers().get("retry-after").map(|v| v.to_str().unwrap().to_string());
                (r.status().as_u16(), retry)
            })
        })
        .collect();
    let results: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
    let shed: Vec<_> = results.iter().filter(|(s, _)| *s == 503).collect();
    assert!(!shed.is_empty(), "{results:?}");
    assert!(results.iter().any(|(s, _)| *s == 201), "{results:?}");
    assert!(shed.iter().all(|(_, r)| r.as_deref() == Some("1")));
    assert_eq!(server.stats().snapshot()[6], shed.len() as u64);
    server.stop();
}

/// Beyond the connection limit a new connection gets 503 and is closed; once one closes, new ones
/// are served again.
#[test]
fn connection_limit_answers_503() {
    use std::io::{Read, Write};
    let config = ServerConfig {
        lanes: 1,
        limits: np_http_server::Limits {
            max_connections: 1,
            ..Default::default()
        },
        ..ServerConfig::default()
    };
    let (server, _base) = serve(config, echo);
    let addr = server.local_addr();
    let get = "GET /x HTTP/1.1\r\nhost: t\r\n\r\n";
    let mut first = std::net::TcpStream::connect(addr).unwrap();
    first.write_all(get.as_bytes()).unwrap();
    let mut buf = [0u8; 256];
    let n = first.read(&mut buf).unwrap();
    assert!(String::from_utf8_lossy(&buf[..n]).starts_with("HTTP/1.1 201"));
    // The first connection is still open (keep-alive): the second is over the limit.
    let mut second = std::net::TcpStream::connect(addr).unwrap();
    let mut answer = String::new();
    second.read_to_string(&mut answer).unwrap();
    assert!(answer.starts_with("HTTP/1.1 503"), "{answer}");
    assert_eq!(server.stats().snapshot()[7], 1);
    drop(first);
    std::thread::sleep(Duration::from_millis(200));
    let resp = reqwest::blocking::get(format!("http://{addr}/again")).unwrap();
    assert_eq!(resp.status().as_u16(), 201);
    server.stop();
}

/// A worker that hands requests to other threads and drives its lane with `drive` while they run:
/// the answers are written without the worker taking new requests.
#[test]
fn drive_writes_answers_from_other_threads() {
    let config = ServerConfig {
        lanes: 1,
        ..ServerConfig::default()
    };
    let server = Arc::new(NativePathServer::start(config).unwrap());
    let base = format!("http://{}", server.local_addr());
    let s = Arc::clone(&server);
    std::thread::spawn(move || loop {
        match s.lane(0).next_request(Duration::from_millis(50)) {
            Next::Request(r) => {
                let t = std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_millis(50));
                    echo(r)
                });
                // In flight limit 1: drive until it is answered.
                while !t.is_finished() {
                    if !s.lane(0).drive(Duration::from_millis(100)) {
                        return;
                    }
                }
            }
            Next::Timeout => continue,
            Next::Stopped => return,
        }
    });
    let client = reqwest::blocking::Client::new();
    for i in 0..5 {
        let r = client.post(format!("{base}/d")).body(format!("{i}")).send().unwrap();
        assert_eq!(r.status().as_u16(), 201);
        assert_eq!(r.text().unwrap(), format!("{i}"));
    }
    server.stop();
}
