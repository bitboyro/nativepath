//! Rust-only benchmark: the engine against a minimal local HTTP/1.1 server, no JVM involved.
//! Shows what the Rust side costs on its own, with its internal timing breakdown.
//!
//!     cargo run --release -p np-http-client --example rust_bench

use np_http_client::alloc::ZeroizingAllocator;
use np_http_client::{ClientConfig, NativePathClient};
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::time::Instant;

#[global_allocator]
static GLOBAL: ZeroizingAllocator<std::alloc::System> = ZeroizingAllocator(std::alloc::System);

fn serve(mut stream: TcpStream) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap_or(0) == 0 {
            return;
        }
        // GET /?size=N HTTP/1.1
        let size: usize = line
            .split("size=")
            .nth(1)
            .and_then(|r| r.split(' ').next())
            .and_then(|n| n.parse().ok())
            .unwrap_or(256);
        loop {
            let mut h = String::new();
            if reader.read_line(&mut h).unwrap_or(0) == 0 {
                return;
            }
            if h == "\r\n" {
                break;
            }
        }
        let mut resp = format!("HTTP/1.1 200 OK\r\nContent-Length: {size}\r\n\r\n").into_bytes();
        resp.resize(resp.len() + size, b'A');
        if stream.write_all(&resp).is_err() {
            return;
        }
    }
}

fn main() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for conn in listener.incoming().flatten() {
            conn.set_nodelay(true).ok();
            std::thread::spawn(move || serve(conn));
        }
    });

    let client = NativePathClient::new(ClientConfig::default()).unwrap();
    const WARMUP: usize = 300;
    const ITERS: usize = 2000;

    println!("Rust-only engine benchmark ({ITERS} requests per size after {WARMUP} warmup)\n");
    println!(
        "{:>9} | {:>9} | {:>10} {:>9} {:>11} {:>10} | {:>9}",
        "payload", "total/req", "headers", "body", "assemble", "drop+wipe", "other"
    );
    println!("\nstreaming read-until-EOF vs read-exact-length (average per request):");
    for size in [256usize, 8 * 1024, 64 * 1024] {
        let url = format!("http://127.0.0.1:{port}/?size={size}");
        let run = |until_eof: bool| {
            let t = Instant::now();
            for _ in 0..500 {
                let mut s = client.get_stream(&url).unwrap();
                let mut got = 0usize;
                loop {
                    if !until_eof && got >= size { break; }
                    match s.next_chunk().unwrap() { Some(c) => got += c.len(), None => break }
                }
            }
            t.elapsed().as_micros() as f64 / 500.0
        };
        let _ = run(true);
        println!("{:>9}: read until EOF {:>8.1}us | stop at length {:>8.1}us", human(size), run(true), run(false));
    }
    println!();
    for size in [256usize, 8 * 1024, 64 * 1024, 512 * 1024, 4 * 1024 * 1024] {
        let url = format!("http://127.0.0.1:{port}/?size={size}");
        for _ in 0..WARMUP {
            drop(client.get(&url).unwrap());
        }
        let (mut total, mut headers, mut body, mut assemble, mut wipe) = (0u128, 0u128, 0u128, 0u128, 0u128);
        let iters = if size >= 4 * 1024 * 1024 { ITERS / 10 } else { ITERS };
        for _ in 0..iters {
            let t = Instant::now();
            let r = client.get(&url).unwrap();
            total += t.elapsed().as_nanos();
            headers += r.timings.headers_ns as u128;
            body += r.timings.body_ns as u128;
            assemble += r.timings.assemble_ns as u128;
            let t = Instant::now();
            drop(r);
            wipe += t.elapsed().as_nanos();
        }
        let n = iters as f64;
        let us = |v: u128| v as f64 / n / 1000.0;
        let other = us(total) - us(headers) - us(body) - us(assemble);
        println!(
            "{:>9} | {:>7.1}us | {:>8.1}us {:>7.1}us {:>9.1}us {:>8.1}us | {:>7.1}us",
            human(size), us(total), us(headers), us(body), us(assemble), us(wipe), other
        );
    }
}

fn human(n: usize) -> String {
    if n >= 1 << 20 { format!("{} MB", n >> 20) } else if n >= 1 << 10 { format!("{} KB", n >> 10) } else { format!("{n} B") }
}
