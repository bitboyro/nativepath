// Shared body of the hyper_direct examples: plain hyper answering "hello" inline on a multi-thread
// tokio runtime (one thread per core). No queue, no handoff, no JVM: the floor for this machine.

use bytes::Bytes;
use http_body_util::Full;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Request, Response};
use hyper_util::rt::TokioIo;
use std::convert::Infallible;

async fn hello(_req: Request<hyper::body::Incoming>) -> Result<Response<Full<Bytes>>, Infallible> {
    let mut r = Response::new(Full::new(Bytes::from_static(b"hello")));
    r.headers_mut().insert(
        "content-type",
        hyper::header::HeaderValue::from_static("text/plain"),
    );
    Ok(r)
}

pub fn run() {
    let port: u16 = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(18094);
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async move {
        let listener = tokio::net::TcpListener::bind(("127.0.0.1", port))
            .await
            .unwrap();
        println!("READY {}", listener.local_addr().unwrap());
        loop {
            let (stream, _) = listener.accept().await.unwrap();
            let _ = stream.set_nodelay(true);
            tokio::spawn(async move {
                let _ = http1::Builder::new()
                    .serve_connection(TokioIo::new(stream), service_fn(hello))
                    .await;
            });
        }
    });
}
