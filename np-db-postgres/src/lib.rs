//! PostgreSQL wire-protocol (v3) engine for NativePath.
//!
//! Blocking, one socket per connection, text format in both directions. The point of writing the
//! wire handling here instead of using a full client crate is buffer control: every buffer that
//! held protocol bytes (the socket read buffer, the current message, the outgoing message) is
//! wiped as soon as a statement completes, so a secret parameter or column does not linger in a
//! pooled connection between statements. Only authentication (SCRAM, MD5) comes from
//! `postgres-protocol`.
//!
//! TLS follows libpq's `sslmode` (see [`tls`]) and SCRAM channel binding (`SCRAM-SHA-256-PLUS`)
//! is used whenever TLS is on and the server offers it. Queries can be cancelled from another
//! thread with a [`CancelToken`].
//!
//! Not supported yet: client certificates, COPY, LISTEN/NOTIFY, cursors (results are read in full).

mod tls;
mod tls_stream;

pub use tls::{ChannelBinding, SslMode};
use tls::{Stream, TlsSetup};

use np_memory::alloc::secure_wipe;
use postgres_protocol::authentication::{md5_hash, sasl};
use std::fmt;
use std::io::{self, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::path::PathBuf;
use std::time::Duration;

const PROTOCOL_V3: i32 = 196_608;
const CANCEL_REQUEST_CODE: i32 = 80_877_102;
const READ_BUF: usize = 64 * 1024;

#[derive(Debug, Clone)]
pub struct Config {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub database: String,
    pub application_name: String,
    pub connect_timeout: Option<Duration>,
    pub ssl_mode: SslMode,
    /// PEM file with the CA certificate(s) that signed the server certificate.
    pub ssl_root_cert: Option<PathBuf>,
    pub channel_binding: ChannelBinding,
}

impl Config {
    /// Plaintext defaults for the given server and user.
    pub fn new(host: &str, port: u16, user: &str, database: &str) -> Config {
        Config {
            host: host.to_string(),
            port,
            user: user.to_string(),
            database: database.to_string(),
            application_name: "NativePath".to_string(),
            connect_timeout: Some(Duration::from_secs(10)),
            ssl_mode: SslMode::Prefer,
            ssl_root_cert: None,
            channel_binding: ChannelBinding::Prefer,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ErrorKind {
    /// Socket failure; the connection is unusable.
    Io = 1,
    /// The server sent something this engine does not understand; the connection is unusable.
    Protocol = 2,
    /// An `ErrorResponse` from the server; the connection is still usable.
    Server = 3,
    /// Authentication could not be completed.
    Auth = 4,
    /// Invalid input from the caller (for example a NUL byte in SQL).
    Usage = 5,
}

#[derive(Debug)]
pub struct DbError {
    pub kind: ErrorKind,
    pub sqlstate: String,
    pub message: String,
    /// The server's DETAIL field. It often quotes row values (for example the duplicate key), so
    /// callers should not surface it by default.
    pub detail: Option<String>,
}

impl DbError {
    fn new(kind: ErrorKind, sqlstate: &str, message: impl Into<String>) -> Self {
        DbError { kind, sqlstate: sqlstate.to_string(), message: message.into(), detail: None }
    }

    fn io(e: io::Error) -> Self {
        DbError::new(ErrorKind::Io, "08006", format!("connection failure: {e}"))
    }

    fn protocol(what: &str) -> Self {
        DbError::new(ErrorKind::Protocol, "08P01", format!("protocol violation: {what}"))
    }
}

impl fmt::Display for DbError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} ({})", self.message, self.sqlstate)
    }
}

impl std::error::Error for DbError {}

/// One bind parameter. `value: None` is SQL NULL; `type_oid: 0` lets the server infer the type.
#[derive(Debug, Clone, Copy)]
pub struct Param<'a> {
    pub value: Option<&'a [u8]>,
    pub type_oid: u32,
}

#[derive(Debug, Clone)]
pub struct Column {
    pub name: String,
    pub table_oid: u32,
    pub type_oid: u32,
}

/// Where one cell's text lives in `QueryResult::data`. `len == -1` is SQL NULL.
#[derive(Debug, Clone, Copy, Default)]
#[repr(C)]
pub struct Cell {
    pub offset: u64,
    pub len: i64,
}

/// One statement's outcome: rows (if the statement returns any) and the command tag.
#[derive(Debug, Default)]
pub struct QueryResult {
    pub columns: Vec<Column>,
    /// Row-major, `nrows * columns.len()` entries.
    pub cells: Vec<Cell>,
    /// All cell bytes, back to back. Wiped on drop.
    pub data: Vec<u8>,
    pub nrows: usize,
    pub has_rows: bool,
    pub tag: String,
    pub rows_affected: i64,
}

impl QueryResult {
    fn with_columns(columns: Vec<Column>) -> Self {
        let mut r = QueryResult::default();
        r.columns = columns;
        r.has_rows = true;
        r
    }
}

impl Drop for QueryResult {
    fn drop(&mut self) {
        wipe_vec(&mut self.data);
    }
}

pub struct Connection {
    stream: Stream,
    user: String,
    channel_binding: ChannelBinding,
    channel_bound: bool,
    cancel: CancelToken,
    inbuf: Vec<u8>,
    start: usize,
    end: usize,
    msg: Vec<u8>,
    out: Vec<u8>,
    tx_status: u8,
    parameters: Vec<(String, String)>,
    broken: bool,
}

impl Connection {
    /// Connects and authenticates. `password` is read in place and never copied into a
    /// long-lived buffer; SCRAM state and protocol buffers are wiped once authentication ends.
    pub fn connect(config: &Config, password: Option<&[u8]>) -> Result<Connection, DbError> {
        for s in [&config.user, &config.database, &config.application_name, &config.host] {
            check_no_nul(s.as_bytes())?;
        }
        let tls = TlsSetup::new(config.ssl_mode, &config.host, config.ssl_root_cert.as_deref())?;
        let sock = open_socket(&config.host, config.port, config.connect_timeout)?;
        let stream = match &tls {
            Some(t) => t.negotiate(sock)?,
            None => Stream::Plain(sock),
        };
        if config.channel_binding == ChannelBinding::Require && stream.end_point().is_none() {
            return Err(DbError::new(ErrorKind::Auth, "08001",
                "channel_binding=require needs TLS with a server certificate whose hash is known"));
        }
        let mut conn = Connection {
            stream,
            user: config.user.clone(),
            channel_binding: config.channel_binding,
            channel_bound: false,
            cancel: CancelToken {
                host: config.host.clone(),
                port: config.port,
                connect_timeout: config.connect_timeout,
                tls,
                pid: 0,
                key: 0,
            },
            inbuf: vec![0u8; READ_BUF],
            start: 0,
            end: 0,
            msg: Vec::new(),
            out: Vec::new(),
            tx_status: b'I',
            parameters: Vec::new(),
            broken: false,
        };
        let result = conn.startup(config, password);
        conn.wipe_buffers();
        result.map(|_| conn)
    }

    fn startup(&mut self, config: &Config, password: Option<&[u8]>) -> Result<(), DbError> {
        let mut body = Vec::new();
        body.extend_from_slice(&PROTOCOL_V3.to_be_bytes());
        for (k, v) in [
            ("user", config.user.as_str()),
            ("database", config.database.as_str()),
            ("application_name", config.application_name.as_str()),
            ("client_encoding", "UTF8"),
            ("DateStyle", "ISO"),
            ("extra_float_digits", "3"),
        ] {
            put_cstr(&mut body, k.as_bytes());
            put_cstr(&mut body, v.as_bytes());
        }
        body.push(0);
        self.out.extend_from_slice(&((body.len() + 4) as i32).to_be_bytes());
        self.out.extend_from_slice(&body);
        self.flush()?;

        // Boxed so the SCRAM state (which holds the normalized password, then the salted
        // password) is freed through the zeroizing allocator rather than left on the stack.
        let mut scram: Option<Box<sasl::ScramSha256>> = None;
        let mut server_error = None;
        loop {
            let tag = self.read_message()?;
            match tag {
                b'R' => self.authenticate(password, &mut scram)?,
                b'S' => self.parameter_status()?,
                b'K' => {
                    let mut r = Reader::new(&self.msg);
                    self.cancel.pid = r.i32()?;
                    self.cancel.key = r.i32()?;
                }
                b'N' => {}
                b'E' => server_error = Some(parse_error(&self.msg)),
                b'Z' => {
                    self.tx_status = *self.msg.first().ok_or_else(|| DbError::protocol("empty ReadyForQuery"))?;
                    break;
                }
                other => return Err(DbError::protocol(&format!("unexpected message '{}' during startup", other as char))),
            }
            if let Some(e) = server_error.take() {
                // Startup errors (bad password, unknown database) end the session.
                return Err(e);
            }
        }
        Ok(())
    }

    fn authenticate(&mut self, password: Option<&[u8]>, scram: &mut Option<Box<sasl::ScramSha256>>) -> Result<(), DbError> {
        let mut r = Reader::new(&self.msg);
        let code = r.i32()?;
        let need_password = || {
            password.ok_or_else(|| DbError::new(ErrorKind::Auth, "28000", "the server requested a password and none was configured"))
        };
        match code {
            0 => {
                if self.channel_binding == ChannelBinding::Require && !self.channel_bound {
                    // The server accepted us without SCRAM-SHA-256-PLUS (trust, password, md5,
                    // or plain SCRAM): it could be a man in the middle.
                    return Err(DbError::new(ErrorKind::Auth, "28000",
                        "channel_binding=require, but the server authenticated without channel binding"));
                }
                Ok(())
            }
            3 | 5 if self.channel_binding == ChannelBinding::Require => Err(DbError::new(ErrorKind::Auth, "28000",
                "channel_binding=require, but the server asked for a password without SCRAM")),
            3 => {
                let pw = need_password()?;
                check_no_nul(pw)?;
                put_message(&mut self.out, b'p', |b| put_cstr(b, pw));
                self.flush()
            }
            5 => {
                let pw = need_password()?;
                let salt: [u8; 4] = r.bytes(4)?.try_into().map_err(|_| DbError::protocol("short MD5 salt"))?;
                let mut hash = md5_hash(self.user.as_bytes(), pw, salt);
                put_message(&mut self.out, b'p', |b| put_cstr(b, hash.as_bytes()));
                // The hash is password-equivalent for this server; wipe the heap copy now.
                unsafe { secure_wipe(hash.as_mut_vec().as_mut_ptr(), hash.len()) };
                self.flush()
            }
            10 => {
                let pw = need_password()?;
                let mut mechanisms = Vec::new();
                while let Some(m) = r.cstr_opt()? {
                    mechanisms.push(m);
                }
                let offers_plus = mechanisms.iter().any(|m| m == "SCRAM-SHA-256-PLUS");
                let end_point = self.stream.end_point().map(|e| e.to_vec());
                let (mechanism, binding): (&[u8], _) = match (self.channel_binding, end_point, offers_plus) {
                    (ChannelBinding::Prefer | ChannelBinding::Require, Some(ep), true) => {
                        self.channel_bound = true;
                        (b"SCRAM-SHA-256-PLUS", sasl::ChannelBinding::tls_server_end_point(ep))
                    }
                    (ChannelBinding::Require, _, _) => {
                        return Err(DbError::new(ErrorKind::Auth, "28000",
                            "channel_binding=require, but the server does not offer SCRAM-SHA-256-PLUS"));
                    }
                    // We could bind but the server did not offer it: say so ("y"), so a server that
                    // does support it can detect a downgrade by a man in the middle.
                    (ChannelBinding::Prefer, Some(_), false) => (b"SCRAM-SHA-256", sasl::ChannelBinding::unrequested()),
                    _ => (b"SCRAM-SHA-256", sasl::ChannelBinding::unsupported()),
                };
                if !mechanisms.iter().any(|m| m.as_bytes() == mechanism) {
                    return Err(DbError::new(ErrorKind::Auth, "28000",
                        format!("no supported SASL mechanism (server offers {mechanisms:?})")));
                }
                let s = Box::new(sasl::ScramSha256::new(pw, binding));
                let first = s.message();
                put_message(&mut self.out, b'p', |b| {
                    put_cstr(b, mechanism);
                    b.extend_from_slice(&(first.len() as i32).to_be_bytes());
                    b.extend_from_slice(first);
                });
                *scram = Some(s);
                self.flush()
            }
            11 => {
                let s = scram.as_mut().ok_or_else(|| DbError::protocol("SASLContinue before SASL start"))?;
                s.update(r.rest()).map_err(|e| DbError::new(ErrorKind::Auth, "28000", format!("SCRAM: {e}")))?;
                let next = s.message();
                put_message(&mut self.out, b'p', |b| b.extend_from_slice(next));
                self.flush()
            }
            12 => {
                let mut s = scram.take().ok_or_else(|| DbError::protocol("SASLFinal before SASL start"))?;
                s.finish(r.rest()).map_err(|e| DbError::new(ErrorKind::Auth, "28000", format!("SCRAM: {e}")))
            }
            other => Err(DbError::new(ErrorKind::Auth, "28000", format!("unsupported authentication method {other}"))),
        }
    }

    fn parameter_status(&mut self) -> Result<(), DbError> {
        let mut r = Reader::new(&self.msg);
        let name = r.cstr()?;
        let value = r.cstr()?;
        match self.parameters.iter_mut().find(|(k, _)| *k == name) {
            Some(entry) => entry.1 = value,
            None => self.parameters.push((name, value)),
        }
        Ok(())
    }

    /// Runs one statement with the extended protocol (Parse, Bind, Describe, Execute, Sync).
    /// Parameters and results use text format. On a server error the connection stays usable.
    pub fn execute(&mut self, sql: &[u8], params: &[Param<'_>]) -> Result<QueryResult, DbError> {
        self.check_usable()?;
        check_no_nul(sql)?;
        if params.len() > i16::MAX as usize {
            return Err(DbError::new(ErrorKind::Usage, "54000", "too many parameters"));
        }
        let n = params.len() as i16;
        put_message(&mut self.out, b'P', |b| {
            put_cstr(b, b"");
            put_cstr(b, sql);
            b.extend_from_slice(&n.to_be_bytes());
            for p in params {
                b.extend_from_slice(&p.type_oid.to_be_bytes());
            }
        });
        put_message(&mut self.out, b'B', |b| {
            put_cstr(b, b"");
            put_cstr(b, b"");
            b.extend_from_slice(&0i16.to_be_bytes()); // all parameters in text format
            b.extend_from_slice(&n.to_be_bytes());
            for p in params {
                match p.value {
                    None => b.extend_from_slice(&(-1i32).to_be_bytes()),
                    Some(v) => {
                        b.extend_from_slice(&(v.len() as i32).to_be_bytes());
                        b.extend_from_slice(v);
                    }
                }
            }
            b.extend_from_slice(&0i16.to_be_bytes()); // all results in text format
        });
        put_message(&mut self.out, b'D', |b| {
            b.push(b'P');
            put_cstr(b, b"");
        });
        put_message(&mut self.out, b'E', |b| {
            put_cstr(b, b"");
            b.extend_from_slice(&0i32.to_be_bytes());
        });
        put_message(&mut self.out, b'S', |_| {});
        let result = self.flush().and_then(|_| self.read_results());
        self.wipe_buffers();
        let mut results = result?;
        Ok(results.pop().unwrap_or_default())
    }

    /// Runs SQL with the simple protocol: no parameters, may contain several statements.
    pub fn simple_query(&mut self, sql: &[u8]) -> Result<Vec<QueryResult>, DbError> {
        self.check_usable()?;
        check_no_nul(sql)?;
        put_message(&mut self.out, b'Q', |b| put_cstr(b, sql));
        let result = self.flush().and_then(|_| self.read_results());
        self.wipe_buffers();
        result
    }

    /// Reads until ReadyForQuery. Returns the first server error after draining.
    fn read_results(&mut self) -> Result<Vec<QueryResult>, DbError> {
        let mut results = Vec::new();
        let mut current: Option<QueryResult> = None;
        let mut error: Option<DbError> = None;
        loop {
            let tag = self.read_message()?;
            match tag {
                b'1' | b'2' | b'N' | b'A' | b't' => {}
                b'n' => {}
                b'S' => self.parameter_status()?,
                b'T' => current = Some(QueryResult::with_columns(parse_row_description(&self.msg)?)),
                b'D' => {
                    let res = current.as_mut().ok_or_else(|| DbError::protocol("DataRow without RowDescription"))?;
                    append_data_row(res, &self.msg)?;
                }
                b'C' | b's' => {
                    let mut res = current.take().unwrap_or_default();
                    if tag == b'C' {
                        res.tag = Reader::new(&self.msg).cstr()?;
                        res.rows_affected = rows_from_tag(&res.tag);
                    }
                    results.push(res);
                }
                b'I' => {
                    current = None;
                }
                b'E' => {
                    current = None;
                    if error.is_none() {
                        error = Some(parse_error(&self.msg));
                    }
                }
                b'Z' => {
                    self.tx_status = *self.msg.first().ok_or_else(|| DbError::protocol("empty ReadyForQuery"))?;
                    break;
                }
                other => {
                    self.broken = true;
                    return Err(DbError::protocol(&format!("unexpected message '{}'", other as char)));
                }
            }
        }
        match error {
            Some(e) => Err(e),
            None => Ok(results),
        }
    }

    /// Transaction status from the last ReadyForQuery: `I` idle, `T` in a transaction, `E` failed transaction.
    pub fn tx_status(&self) -> u8 {
        self.tx_status
    }

    pub fn is_broken(&self) -> bool {
        self.broken
    }

    /// A server parameter reported at startup or later, such as `server_version`.
    pub fn parameter(&self, name: &str) -> Option<&str> {
        self.parameters.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
    }

    pub fn set_timeout(&self, timeout: Option<Duration>) -> Result<(), DbError> {
        self.stream.socket().set_read_timeout(timeout).map_err(DbError::io)?;
        self.stream.socket().set_write_timeout(timeout).map_err(DbError::io)
    }

    /// True when the connection is encrypted.
    pub fn is_tls(&self) -> bool {
        self.stream.is_tls()
    }

    /// True when authentication used SCRAM-SHA-256-PLUS (bound to this TLS session).
    pub fn is_channel_bound(&self) -> bool {
        self.channel_bound
    }

    /// A handle that can cancel the statement running on this connection, from any thread.
    pub fn cancel_token(&self) -> CancelToken {
        self.cancel.clone()
    }

    /// Sends Terminate (and a TLS close_notify) and closes the socket. Buffers are wiped on drop.
    pub fn close(mut self) {
        if !self.broken {
            put_message(&mut self.out, b'X', |_| {});
            let _ = self.flush();
            self.stream.close_notify();
        }
    }

    fn check_usable(&self) -> Result<(), DbError> {
        if self.broken {
            return Err(DbError::new(ErrorKind::Io, "08003", "the connection is broken"));
        }
        Ok(())
    }

    fn flush(&mut self) -> Result<(), DbError> {
        let r = self.stream.write_all(&self.out).and_then(|_| self.stream.flush());
        wipe_vec(&mut self.out);
        r.map_err(|e| {
            self.broken = true;
            DbError::io(e)
        })
    }

    /// Reads one backend message into `self.msg` and returns its type byte.
    fn read_message(&mut self) -> Result<u8, DbError> {
        let mut header = [0u8; 5];
        self.read_exact(&mut header)?;
        let len = i32::from_be_bytes([header[1], header[2], header[3], header[4]]);
        if len < 4 {
            self.broken = true;
            return Err(DbError::protocol("negative message length"));
        }
        let body = (len - 4) as usize;
        self.msg.clear();
        self.msg.reserve(body);
        let mut remaining = body;
        while remaining > 0 {
            if self.start == self.end {
                self.fill()?;
            }
            let k = remaining.min(self.end - self.start);
            self.msg.extend_from_slice(&self.inbuf[self.start..self.start + k]);
            self.start += k;
            remaining -= k;
        }
        Ok(header[0])
    }

    fn read_exact(&mut self, out: &mut [u8]) -> Result<(), DbError> {
        let mut filled = 0;
        while filled < out.len() {
            if self.start == self.end {
                self.fill()?;
            }
            let k = (out.len() - filled).min(self.end - self.start);
            out[filled..filled + k].copy_from_slice(&self.inbuf[self.start..self.start + k]);
            self.start += k;
            filled += k;
        }
        Ok(())
    }

    fn fill(&mut self) -> Result<(), DbError> {
        self.start = 0;
        self.end = 0;
        match self.stream.read(&mut self.inbuf) {
            Ok(0) => {
                self.broken = true;
                Err(DbError::io(io::Error::new(io::ErrorKind::UnexpectedEof, "server closed the connection")))
            }
            Ok(n) => {
                self.end = n;
                Ok(())
            }
            Err(e) => {
                self.broken = true;
                Err(DbError::io(e))
            }
        }
    }

    /// Wipes every buffer that held protocol bytes, keeping any unread bytes (an asynchronous
    /// notice that arrived after ReadyForQuery) at the front of the read buffer.
    fn wipe_buffers(&mut self) {
        let pending = self.end - self.start;
        if pending > 0 && self.start > 0 {
            self.inbuf.copy_within(self.start..self.end, 0);
        }
        secure_wipe(self.inbuf[pending..].as_mut_ptr(), self.inbuf.len() - pending);
        self.start = 0;
        self.end = pending;
        wipe_vec(&mut self.msg);
        wipe_vec(&mut self.out);
        self.stream.wipe();
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        secure_wipe(self.inbuf.as_mut_ptr(), self.inbuf.len());
        wipe_vec(&mut self.msg);
        wipe_vec(&mut self.out);
    }
}

/// Cancels the statement running on a connection by sending a CancelRequest over a new
/// connection (with the same TLS settings), as libpq does. The server answers by closing the
/// socket; waiting for that guarantees the signal was delivered before `cancel` returns, so a
/// late cancel cannot hit the next statement.
#[derive(Clone)]
pub struct CancelToken {
    host: String,
    port: u16,
    connect_timeout: Option<Duration>,
    tls: Option<TlsSetup>,
    pid: i32,
    key: i32,
}

impl CancelToken {
    pub fn cancel(&self) -> Result<(), DbError> {
        let sock = open_socket(&self.host, self.port, self.connect_timeout)?;
        let _ = sock.set_read_timeout(Some(self.connect_timeout.unwrap_or(Duration::from_secs(10))));
        let mut stream = match &self.tls {
            Some(t) => t.negotiate(sock)?,
            None => Stream::Plain(sock),
        };
        stream.write_all(&cancel_request(self.pid, self.key)).and_then(|_| stream.flush()).map_err(DbError::io)?;
        let mut sink = [0u8; 64];
        loop {
            match stream.read(&mut sink) {
                Ok(0) | Err(_) => return Ok(()),
                Ok(_) => {}
            }
        }
    }
}

fn cancel_request(pid: i32, key: i32) -> [u8; 16] {
    let mut m = [0u8; 16];
    m[0..4].copy_from_slice(&16i32.to_be_bytes());
    m[4..8].copy_from_slice(&CANCEL_REQUEST_CODE.to_be_bytes());
    m[8..12].copy_from_slice(&pid.to_be_bytes());
    m[12..16].copy_from_slice(&key.to_be_bytes());
    m
}

fn open_socket(host: &str, port: u16, connect_timeout: Option<Duration>) -> Result<TcpStream, DbError> {
    let addrs = (host, port)
        .to_socket_addrs()
        .map_err(|e| DbError::new(ErrorKind::Io, "08001", format!("cannot resolve {host}: {e}")))?;
    let mut last = None;
    for addr in addrs {
        let attempt = match connect_timeout {
            Some(t) => TcpStream::connect_timeout(&addr, t),
            None => TcpStream::connect(addr),
        };
        match attempt {
            Ok(s) => {
                let _ = s.set_nodelay(true);
                return Ok(s);
            }
            Err(e) => last = Some(e),
        }
    }
    let why = last.map(|e| e.to_string()).unwrap_or_else(|| "no addresses".into());
    Err(DbError::new(ErrorKind::Io, "08001", format!("cannot connect to {host}:{port}: {why}")))
}

/// Zeroes a vector's whole allocation (not only its length) and empties it.
fn wipe_vec(v: &mut Vec<u8>) {
    secure_wipe(v.as_mut_ptr(), v.capacity());
    v.clear();
}

fn check_no_nul(s: &[u8]) -> Result<(), DbError> {
    if s.contains(&0) {
        return Err(DbError::new(ErrorKind::Usage, "22021", "value contains a NUL byte"));
    }
    Ok(())
}

fn put_cstr(b: &mut Vec<u8>, s: &[u8]) {
    b.extend_from_slice(s);
    b.push(0);
}

fn put_message(out: &mut Vec<u8>, tag: u8, body: impl FnOnce(&mut Vec<u8>)) {
    out.push(tag);
    let at = out.len();
    out.extend_from_slice(&[0; 4]);
    body(out);
    let len = (out.len() - at) as i32;
    out[at..at + 4].copy_from_slice(&len.to_be_bytes());
}

fn parse_row_description(msg: &[u8]) -> Result<Vec<Column>, DbError> {
    let mut r = Reader::new(msg);
    let n = r.i16()?;
    let mut cols = Vec::with_capacity(n.max(0) as usize);
    for _ in 0..n {
        let name = r.cstr()?;
        let table_oid = r.u32()?;
        let _attnum = r.i16()?;
        let type_oid = r.u32()?;
        let _typlen = r.i16()?;
        let _typmod = r.i32()?;
        let _format = r.i16()?;
        cols.push(Column { name, table_oid, type_oid });
    }
    Ok(cols)
}

fn append_data_row(res: &mut QueryResult, msg: &[u8]) -> Result<(), DbError> {
    let mut r = Reader::new(msg);
    let n = r.i16()?;
    if n as usize != res.columns.len() {
        return Err(DbError::protocol("DataRow column count differs from RowDescription"));
    }
    for _ in 0..n {
        let len = r.i32()?;
        if len < 0 {
            res.cells.push(Cell { offset: 0, len: -1 });
        } else {
            let bytes = r.bytes(len as usize)?;
            res.cells.push(Cell { offset: res.data.len() as u64, len: len as i64 });
            res.data.extend_from_slice(bytes);
        }
    }
    res.nrows += 1;
    Ok(())
}

fn rows_from_tag(tag: &str) -> i64 {
    // "INSERT 0 5", "UPDATE 3", "DELETE 1", "SELECT 2", "MERGE 4", "COPY 9"; DDL tags have no count.
    let mut parts = tag.split(' ');
    match parts.next() {
        Some("INSERT" | "UPDATE" | "DELETE" | "SELECT" | "MERGE" | "COPY" | "MOVE" | "FETCH") => {
            tag.rsplit(' ').next().and_then(|n| n.parse().ok()).unwrap_or(0)
        }
        _ => 0,
    }
}

fn parse_error(msg: &[u8]) -> DbError {
    let mut e = DbError::new(ErrorKind::Server, "XX000", "");
    let mut r = Reader::new(msg);
    let mut severity = String::new();
    while let Ok(code) = r.u8() {
        if code == 0 {
            break;
        }
        let Ok(value) = r.cstr() else { break };
        match code {
            b'V' => severity = value,
            b'S' if severity.is_empty() => severity = value,
            b'C' => e.sqlstate = value,
            b'M' => e.message = value,
            b'D' => e.detail = Some(value),
            _ => {}
        }
    }
    if !severity.is_empty() {
        e.message = format!("{severity}: {}", e.message);
    }
    if e.sqlstate.starts_with("28") {
        e.kind = ErrorKind::Auth;
    }
    e
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    fn bytes(&mut self, n: usize) -> Result<&'a [u8], DbError> {
        if self.buf.len() - self.pos < n {
            return Err(DbError::protocol("message shorter than its fields"));
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    fn u8(&mut self) -> Result<u8, DbError> {
        Ok(self.bytes(1)?[0])
    }

    fn i16(&mut self) -> Result<i16, DbError> {
        Ok(i16::from_be_bytes(self.bytes(2)?.try_into().unwrap()))
    }

    fn i32(&mut self) -> Result<i32, DbError> {
        Ok(i32::from_be_bytes(self.bytes(4)?.try_into().unwrap()))
    }

    fn u32(&mut self) -> Result<u32, DbError> {
        Ok(u32::from_be_bytes(self.bytes(4)?.try_into().unwrap()))
    }

    fn cstr(&mut self) -> Result<String, DbError> {
        let rest = &self.buf[self.pos..];
        let end = rest.iter().position(|&c| c == 0).ok_or_else(|| DbError::protocol("unterminated string"))?;
        let s = String::from_utf8_lossy(&rest[..end]).into_owned();
        self.pos += end + 1;
        Ok(s)
    }

    /// A string from a list terminated by an empty string.
    fn cstr_opt(&mut self) -> Result<Option<String>, DbError> {
        let s = self.cstr()?;
        Ok(if s.is_empty() { None } else { Some(s) })
    }

    fn rest(&mut self) -> &'a [u8] {
        let s = &self.buf[self.pos..];
        self.pos = self.buf.len();
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_from_tags() {
        assert_eq!(rows_from_tag("INSERT 0 5"), 5);
        assert_eq!(rows_from_tag("UPDATE 3"), 3);
        assert_eq!(rows_from_tag("SELECT 2"), 2);
        assert_eq!(rows_from_tag("CREATE TABLE"), 0);
    }

    #[test]
    fn error_fields() {
        let mut m = Vec::new();
        for (c, v) in [(b'S', "ERROR"), (b'V', "ERROR"), (b'C', "23505"), (b'M', "duplicate key"), (b'D', "Key (id)=(1)")] {
            m.push(c);
            put_cstr(&mut m, v.as_bytes());
        }
        m.push(0);
        let e = parse_error(&m);
        assert_eq!(e.sqlstate, "23505");
        assert_eq!(e.message, "ERROR: duplicate key");
        assert_eq!(e.detail.as_deref(), Some("Key (id)=(1)"));
        assert_eq!(e.kind, ErrorKind::Server);
    }

    #[test]
    fn data_rows_with_nulls() {
        let mut res = QueryResult::with_columns(vec![Column { name: "a".into(), table_oid: 0, type_oid: 25 }; 2]);
        let mut m = Vec::new();
        m.extend_from_slice(&2i16.to_be_bytes());
        m.extend_from_slice(&3i32.to_be_bytes());
        m.extend_from_slice(b"abc");
        m.extend_from_slice(&(-1i32).to_be_bytes());
        append_data_row(&mut res, &m).unwrap();
        assert_eq!(res.nrows, 1);
        assert_eq!(res.cells[0].len, 3);
        assert_eq!(&res.data[..3], b"abc");
        assert_eq!(res.cells[1].len, -1);
    }

    #[test]
    fn cancel_request_layout() {
        let m = cancel_request(0x0102_0304, -1);
        assert_eq!(&m[0..4], &[0, 0, 0, 16]);
        assert_eq!(&m[4..8], &[0x04, 0xd2, 0x16, 0x2e]);
        assert_eq!(&m[8..12], &[1, 2, 3, 4]);
        assert_eq!(&m[12..16], &[0xff; 4]);
    }

    #[test]
    fn message_framing() {
        let mut out = Vec::new();
        put_message(&mut out, b'Q', |b| put_cstr(b, b"SELECT 1"));
        assert_eq!(out[0], b'Q');
        assert_eq!(i32::from_be_bytes(out[1..5].try_into().unwrap()) as usize, out.len() - 1);
    }
}
