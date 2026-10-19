//! TLS negotiation, certificate checks, channel-binding policy and cancel requests against an
//! in-process fake server that speaks just enough of the protocol. No PostgreSQL needed.

use rcgen::{BasicConstraints, CertificateParams, DnType, IsCa, KeyPair};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::mpsc;
use std::sync::Arc;
use std::time::Duration;
use np_db_postgres::{ChannelBinding, Config, Connection, ErrorKind, SslMode};

const PID: i32 = 4242;
const KEY: i32 = 77;

/// What the fake server saw on one connection.
#[derive(Debug, PartialEq)]
enum Event {
    Startup { tls: bool },
    Cancel { tls: bool, pid: i32, key: i32 },
}

struct Pki {
    ca_pem: PathBuf,
    other_ca_pem: PathBuf,
    server: Arc<ServerConfig>,
}

fn pki() -> Pki {
    let dir = std::env::temp_dir().join(format!("np-tls-{}-{:?}", std::process::id(), std::thread::current().id()));
    std::fs::create_dir_all(&dir).unwrap();
    let (ca, ca_key) = make_ca("np test CA");
    let (other, _) = make_ca("someone else's CA");
    let leaf_key = KeyPair::generate().unwrap();
    let leaf = CertificateParams::new(vec!["localhost".to_string()]).unwrap().signed_by(&leaf_key, &ca, &ca_key).unwrap();
    let ca_pem = dir.join("ca.pem");
    let other_ca_pem = dir.join("other.pem");
    std::fs::write(&ca_pem, ca.pem()).unwrap();
    std::fs::write(&other_ca_pem, other.pem()).unwrap();
    let server = ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(
            vec![leaf.der().clone()],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(leaf_key.serialize_der())),
        )
        .unwrap();
    Pki { ca_pem, other_ca_pem, server: Arc::new(server) }
}

fn make_ca(name: &str) -> (rcgen::Certificate, KeyPair) {
    let key = KeyPair::generate().unwrap();
    let mut p = CertificateParams::new(Vec::<String>::new()).unwrap();
    p.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    p.distinguished_name.push(DnType::CommonName, name);
    (p.self_signed(&key).unwrap(), key)
}

trait Duplex: Read + Write + Send {}
impl<T: Read + Write + Send> Duplex for T {}

/// Starts the fake server. `accept_tls` decides the answer to SSLRequest ('S' or 'N').
fn fake_server(tls: Option<Arc<ServerConfig>>, accept_tls: bool) -> (u16, mpsc::Receiver<Event>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        for sock in listener.incoming() {
            let Ok(sock) = sock else { return };
            let tx = tx.clone();
            let tls = tls.clone();
            std::thread::spawn(move || {
                let _ = handle(sock, tls, accept_tls, tx);
            });
        }
    });
    (port, rx)
}

fn read_startup(s: &mut dyn Duplex) -> std::io::Result<(i32, Vec<u8>)> {
    let mut len = [0u8; 4];
    s.read_exact(&mut len)?;
    let mut body = vec![0u8; i32::from_be_bytes(len) as usize - 4];
    s.read_exact(&mut body)?;
    let code = i32::from_be_bytes(body[0..4].try_into().unwrap());
    Ok((code, body))
}

fn handle(mut sock: TcpStream, tls: Option<Arc<ServerConfig>>, accept_tls: bool, tx: mpsc::Sender<Event>) -> std::io::Result<()> {
    sock.set_read_timeout(Some(Duration::from_secs(5)))?;
    let (mut code, mut body) = read_startup(&mut sock)?;
    let mut stream: Box<dyn Duplex> = Box::new(sock.try_clone()?);
    let mut encrypted = false;
    if code == 80_877_103 {
        if accept_tls && tls.is_some() {
            sock.write_all(b"S")?;
            let conn = ServerConnection::new(tls.unwrap()).unwrap();
            stream = Box::new(StreamOwned::new(conn, sock));
            encrypted = true;
        } else {
            sock.write_all(b"N")?;
        }
        (code, body) = read_startup(&mut *stream)?;
    }
    if code == 80_877_102 {
        let pid = i32::from_be_bytes(body[4..8].try_into().unwrap());
        let key = i32::from_be_bytes(body[8..12].try_into().unwrap());
        tx.send(Event::Cancel { tls: encrypted, pid, key }).unwrap();
        return Ok(()); // closing the socket tells the client the request was processed
    }
    assert_eq!(code, 196_608, "expected a v3 startup message");
    tx.send(Event::Startup { tls: encrypted }).unwrap();
    let mut out = Vec::new();
    msg(&mut out, b'R', &0i32.to_be_bytes()); // AuthenticationOk ("trust")
    let mut k = PID.to_be_bytes().to_vec();
    k.extend_from_slice(&KEY.to_be_bytes());
    msg(&mut out, b'K', &k);
    msg(&mut out, b'Z', b"I");
    stream.write_all(&out)?;
    stream.flush()?;
    loop {
        let mut head = [0u8; 5];
        stream.read_exact(&mut head)?;
        let mut rest = vec![0u8; i32::from_be_bytes(head[1..5].try_into().unwrap()) as usize - 4];
        stream.read_exact(&mut rest)?;
        match head[0] {
            b'Q' => {
                let value: &[u8] = if encrypted { b"tls" } else { b"plain" };
                let mut out = Vec::new();
                let mut t = 1i16.to_be_bytes().to_vec();
                t.extend_from_slice(b"x\0");
                t.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 25, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0, 0]);
                msg(&mut out, b'T', &t);
                let mut d = 1i16.to_be_bytes().to_vec();
                d.extend_from_slice(&(value.len() as i32).to_be_bytes());
                d.extend_from_slice(value);
                msg(&mut out, b'D', &d);
                msg(&mut out, b'C', b"SELECT 1\0");
                msg(&mut out, b'Z', b"I");
                stream.write_all(&out)?;
                stream.flush()?;
            }
            b'X' => return Ok(()),
            _ => {}
        }
    }
}

fn msg(out: &mut Vec<u8>, tag: u8, body: &[u8]) {
    out.push(tag);
    out.extend_from_slice(&((body.len() + 4) as i32).to_be_bytes());
    out.extend_from_slice(body);
}

fn config(host: &str, port: u16, mode: SslMode, root: Option<&PathBuf>) -> Config {
    let mut c = Config::new(host, port, "np", "np");
    c.ssl_mode = mode;
    c.ssl_root_cert = root.cloned();
    c.connect_timeout = Some(Duration::from_secs(5));
    c
}

fn query_value(conn: &mut Connection) -> String {
    let r = conn.simple_query(b"SELECT x").unwrap();
    let c = r[0].cells[0];
    String::from_utf8(r[0].data[c.offset as usize..(c.offset as i64 + c.len) as usize].to_vec()).unwrap()
}

fn next(rx: &mpsc::Receiver<Event>) -> Event {
    rx.recv_timeout(Duration::from_secs(5)).expect("server event")
}

#[test]
fn verify_full_with_the_right_ca_and_name() {
    let pki = pki();
    let (port, rx) = fake_server(Some(pki.server.clone()), true);
    let mut conn = Connection::connect(&config("localhost", port, SslMode::VerifyFull, Some(&pki.ca_pem)), None).unwrap();
    assert!(conn.is_tls());
    assert!(!conn.is_channel_bound(), "trust auth: nothing to bind");
    assert_eq!(next(&rx), Event::Startup { tls: true });
    assert_eq!(query_value(&mut conn), "tls");
    conn.close();
}

#[test]
fn verify_full_rejects_a_name_mismatch_but_verify_ca_accepts_it() {
    let pki = pki();
    let (port, _rx) = fake_server(Some(pki.server.clone()), true);
    // The certificate is for "localhost"; connecting by IP does not match.
    let err = Connection::connect(&config("127.0.0.1", port, SslMode::VerifyFull, Some(&pki.ca_pem)), None).err().unwrap();
    assert_eq!(err.sqlstate, "08001");
    assert!(err.message.contains("handshake"), "{}", err.message);
    let conn = Connection::connect(&config("127.0.0.1", port, SslMode::VerifyCa, Some(&pki.ca_pem)), None).unwrap();
    assert!(conn.is_tls());
}

#[test]
fn verify_rejects_an_unknown_ca() {
    let pki = pki();
    let (port, _rx) = fake_server(Some(pki.server.clone()), true);
    for mode in [SslMode::VerifyFull, SslMode::VerifyCa, SslMode::Require] {
        // require + sslrootcert behaves like verify-ca.
        let err = Connection::connect(&config("localhost", port, mode, Some(&pki.other_ca_pem)), None).err().unwrap();
        assert!(err.message.contains("handshake"), "{mode:?}: {}", err.message);
    }
}

#[test]
fn require_without_a_root_certificate_encrypts_without_verifying() {
    let pki = pki();
    let (port, rx) = fake_server(Some(pki.server.clone()), true);
    let mut conn = Connection::connect(&config("127.0.0.1", port, SslMode::Require, None), None).unwrap();
    assert!(conn.is_tls());
    assert_eq!(next(&rx), Event::Startup { tls: true });
    assert_eq!(query_value(&mut conn), "tls");
}

#[test]
fn verify_modes_need_a_root_certificate() {
    for mode in [SslMode::VerifyCa, SslMode::VerifyFull] {
        let err = Connection::connect(&config("127.0.0.1", 1, mode, None), None).err().unwrap();
        assert!(err.message.contains("sslrootcert"), "{}", err.message);
    }
}

#[test]
fn a_server_without_tls() {
    let (port, rx) = fake_server(None, false);
    let err = Connection::connect(&config("127.0.0.1", port, SslMode::Require, None), None).err().unwrap();
    assert!(err.message.contains("does not support TLS"), "{}", err.message);
    let mut conn = Connection::connect(&config("127.0.0.1", port, SslMode::Prefer, None), None).unwrap();
    assert!(!conn.is_tls());
    assert_eq!(next(&rx), Event::Startup { tls: false });
    assert_eq!(query_value(&mut conn), "plain");
}

#[test]
fn disable_never_sends_ssl_request() {
    let pki = pki();
    let (port, rx) = fake_server(Some(pki.server.clone()), true);
    let mut conn = Connection::connect(&config("127.0.0.1", port, SslMode::Disable, None), None).unwrap();
    assert!(!conn.is_tls());
    assert_eq!(next(&rx), Event::Startup { tls: false });
    assert_eq!(query_value(&mut conn), "plain");
}

#[test]
fn cancel_request_goes_over_a_new_tls_connection() {
    let pki = pki();
    let (port, rx) = fake_server(Some(pki.server.clone()), true);
    let conn = Connection::connect(&config("localhost", port, SslMode::VerifyFull, Some(&pki.ca_pem)), None).unwrap();
    assert_eq!(next(&rx), Event::Startup { tls: true });
    conn.cancel_token().cancel().unwrap();
    assert_eq!(next(&rx), Event::Cancel { tls: true, pid: PID, key: KEY });
}

#[test]
fn cancel_request_over_plaintext() {
    let (port, rx) = fake_server(None, false);
    let conn = Connection::connect(&config("127.0.0.1", port, SslMode::Disable, None), None).unwrap();
    assert_eq!(next(&rx), Event::Startup { tls: false });
    let token = conn.cancel_token();
    // The token works from another thread.
    std::thread::spawn(move || token.cancel().unwrap()).join().unwrap();
    assert_eq!(next(&rx), Event::Cancel { tls: false, pid: PID, key: KEY });
}

#[test]
fn channel_binding_require_refuses_a_server_that_skips_scram() {
    let pki = pki();
    let (port, _rx) = fake_server(Some(pki.server.clone()), true);
    let mut c = config("localhost", port, SslMode::VerifyFull, Some(&pki.ca_pem));
    c.channel_binding = ChannelBinding::Require;
    let err = Connection::connect(&c, Some(b"pw")).err().unwrap();
    assert_eq!(err.kind, ErrorKind::Auth);
    assert!(err.message.contains("without channel binding"), "{}", err.message);

    // Without TLS there is nothing to bind to.
    let (port, _rx) = fake_server(None, false);
    let mut c = config("127.0.0.1", port, SslMode::Prefer, None);
    c.channel_binding = ChannelBinding::Require;
    let err = Connection::connect(&c, Some(b"pw")).err().unwrap();
    assert!(err.message.contains("channel_binding=require"), "{}", err.message);
}

#[test]
fn certificate_der_type_matches() {
    // rcgen and rustls must agree on the certificate type used above.
    let _: fn(&rcgen::Certificate) -> &CertificateDer<'static> = rcgen::Certificate::der;
}
