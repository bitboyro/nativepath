//! A TLS client stream on rustls's unbuffered API, so that every buffer holding decrypted data is
//! ours and can be wiped.
//!
//! The buffered `rustls::ClientConnection` decrypts records in place inside its own receive buffer
//! and, once the data is consumed, only resets a length counter: the last plaintext received stays
//! in memory until the next read overwrites it, which on an idle pooled connection can be hours.
//! Here rustls decrypts in place inside `incoming`, which we own: discarded bytes are zeroed
//! immediately, and [`TlsStream::wipe`] zeroes the rest after every statement.

use np_memory::alloc::secure_wipe;
use rustls::client::UnbufferedClientConnection;
use rustls::pki_types::CertificateDer;
use rustls::unbuffered::{ConnectionState, EncodeError, EncryptError, UnbufferedStatus};
use std::io::{self, Read, Write};
use std::net::TcpStream;

/// Room for one maximal TLS record (16 KiB plaintext plus overhead) with space to spare.
const INCOMING: usize = 34 * 1024;

pub(crate) struct TlsStream {
    conn: UnbufferedClientConnection,
    pub(crate) sock: TcpStream,
    /// TLS bytes from the socket; `[..in_used]` is valid. Records are decrypted in place here.
    incoming: Vec<u8>,
    in_used: usize,
    /// Decrypted application data not yet handed to the caller: `plain[p_start..]`.
    plain: Vec<u8>,
    p_start: usize,
    /// Encoded or encrypted TLS bytes waiting to be written.
    outgoing: Vec<u8>,
    peer_closed: bool,
}

/// What one `process_tls_records` step produced.
enum Step {
    /// Records were handled (data decrypted or handshake bytes sent); go round again.
    Progress,
    /// More bytes from the socket are needed.
    NeedRead,
    /// The handshake is done and nothing is pending: application data can be encrypted.
    Writable,
    Closed,
}

fn tls_io(e: impl std::fmt::Display) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("TLS: {e}"))
}

impl TlsStream {
    /// Runs the handshake over `sock`.
    pub fn connect(conn: UnbufferedClientConnection, sock: TcpStream) -> io::Result<TlsStream> {
        let mut s = TlsStream {
            conn,
            sock,
            incoming: vec![0u8; INCOMING],
            in_used: 0,
            plain: Vec::new(),
            p_start: 0,
            outgoing: Vec::new(),
            peer_closed: false,
        };
        loop {
            match s.step()? {
                Step::Writable => break,
                Step::Progress => {}
                Step::NeedRead => s.fill()?,
                Step::Closed => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "TLS: closed during the handshake")),
            }
        }
        s.wipe();
        Ok(s)
    }

    pub fn peer_certificates(&self) -> Option<&[CertificateDer<'static>]> {
        self.conn.peer_certificates()
    }

    /// One call to `process_tls_records`, handling whatever state it returns.
    fn step(&mut self) -> io::Result<Step> {
        let TlsStream { conn, sock, incoming, in_used, plain, outgoing, peer_closed, .. } = self;
        let UnbufferedStatus { mut discard, state } = conn.process_tls_records(&mut incoming[..*in_used]);
        let step = match state.map_err(tls_io)? {
            ConnectionState::ReadTraffic(mut traffic) => {
                while let Some(record) = traffic.next_record() {
                    let record = record.map_err(tls_io)?;
                    discard += record.discard;
                    plain.extend_from_slice(record.payload);
                }
                Step::Progress
            }
            ConnectionState::EncodeTlsData(mut enc) => {
                let used = outgoing.len();
                loop {
                    match enc.encode(&mut outgoing[used..]) {
                        Ok(n) => {
                            outgoing.truncate(used + n);
                            break;
                        }
                        Err(EncodeError::InsufficientSize(e)) => outgoing.resize(used + e.required_size, 0),
                        Err(e) => return Err(tls_io(e)),
                    }
                }
                Step::Progress
            }
            ConnectionState::TransmitTlsData(tx) => {
                let r = sock.write_all(outgoing).and_then(|_| sock.flush());
                wipe_vec(outgoing);
                r?;
                tx.done();
                Step::Progress
            }
            ConnectionState::BlockedHandshake => Step::NeedRead,
            ConnectionState::WriteTraffic(_) => Step::Writable,
            ConnectionState::PeerClosed | ConnectionState::Closed => {
                *peer_closed = true;
                Step::Closed
            }
            _ => return Err(tls_io("unexpected connection state")),
        };
        self.discard(discard);
        Ok(step)
    }

    /// Removes `n` processed bytes from the front of `incoming` and zeroes what they leave behind
    /// (records there were decrypted in place, so this is where plaintext would linger).
    fn discard(&mut self, n: usize) {
        if n == 0 {
            return;
        }
        let n = n.min(self.in_used);
        self.incoming.copy_within(n..self.in_used, 0);
        let remaining = self.in_used - n;
        secure_wipe(self.incoming[remaining..self.in_used].as_mut_ptr(), self.in_used - remaining);
        self.in_used = remaining;
    }

    /// Reads more TLS bytes from the socket into `incoming`.
    fn fill(&mut self) -> io::Result<()> {
        if self.in_used == self.incoming.len() {
            // A record larger than the buffer (only possible during the handshake, up to 64 KiB).
            if self.incoming.len() >= 128 * 1024 {
                return Err(tls_io("record too large"));
            }
            let mut bigger = vec![0u8; self.incoming.len() * 2];
            bigger[..self.in_used].copy_from_slice(&self.incoming[..self.in_used]);
            secure_wipe(self.incoming.as_mut_ptr(), self.incoming.len());
            self.incoming = bigger;
        }
        let n = self.sock.read(&mut self.incoming[self.in_used..])?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "server closed the connection"));
        }
        self.in_used += n;
        Ok(())
    }

    /// Zeroes everything already handed out: consumed plaintext, stale bytes in `incoming`
    /// beyond the unprocessed ones, and `outgoing`. Unconsumed data is kept.
    pub fn wipe(&mut self) {
        if self.p_start > 0 {
            let pending = self.plain.len() - self.p_start;
            self.plain.copy_within(self.p_start.., 0);
            secure_wipe(self.plain[pending..].as_mut_ptr(), self.plain.len() - pending);
            self.plain.truncate(pending);
            self.p_start = 0;
        }
        if self.plain.is_empty() {
            wipe_vec(&mut self.plain);
        }
        let len = self.incoming.len();
        secure_wipe(self.incoming[self.in_used..].as_mut_ptr(), len - self.in_used);
        wipe_vec(&mut self.outgoing);
    }

    /// Sends a close_notify alert (best effort).
    pub fn close_notify(&mut self) {
        loop {
            match self.step() {
                Ok(Step::Writable) => break,
                Ok(Step::Progress) => {}
                _ => return,
            }
        }
        let TlsStream { conn, incoming, in_used, outgoing, .. } = self;
        let UnbufferedStatus { state, .. } = conn.process_tls_records(&mut incoming[..*in_used]);
        if let Ok(ConnectionState::WriteTraffic(mut w)) = state {
            outgoing.resize(64, 0);
            if let Ok(n) = w.queue_close_notify(outgoing) {
                let _ = self.sock.write_all(&self.outgoing[..n]);
            }
        }
        wipe_vec(&mut self.outgoing);
    }
}

impl Read for TlsStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            let available = self.plain.len() - self.p_start;
            if available > 0 {
                let n = available.min(buf.len());
                buf[..n].copy_from_slice(&self.plain[self.p_start..self.p_start + n]);
                // Zero what was handed out right away; the copy in `buf` is the caller's to wipe.
                secure_wipe(self.plain[self.p_start..self.p_start + n].as_mut_ptr(), n);
                self.p_start += n;
                if self.p_start == self.plain.len() {
                    self.plain.clear();
                    self.p_start = 0;
                }
                return Ok(n);
            }
            if self.peer_closed {
                return Ok(0);
            }
            match self.step()? {
                Step::Progress => {}
                Step::NeedRead | Step::Writable => self.fill()?,
                Step::Closed => return Ok(0),
            }
        }
    }
}

impl Write for TlsStream {
    /// Encrypts all of `data` into `outgoing` and sends it.
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        loop {
            match self.step()? {
                Step::Writable => break,
                Step::Progress => {}
                Step::NeedRead => self.fill()?,
                Step::Closed => return Err(io::Error::new(io::ErrorKind::BrokenPipe, "TLS connection closed")),
            }
        }
        let TlsStream { conn, incoming, in_used, outgoing, .. } = self;
        let UnbufferedStatus { state, .. } = conn.process_tls_records(&mut incoming[..*in_used]);
        let Ok(ConnectionState::WriteTraffic(mut w)) = state else {
            return Err(tls_io("connection is not writable"));
        };
        let n = loop {
            match w.encrypt(data, outgoing) {
                Ok(n) => break n,
                Err(EncryptError::InsufficientSize(e)) => outgoing.resize(e.required_size, 0),
                Err(e) => return Err(tls_io(e)),
            }
        };
        let r = self.sock.write_all(&self.outgoing[..n]);
        wipe_vec(&mut self.outgoing);
        r.map(|_| data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.sock.flush()
    }
}

impl Drop for TlsStream {
    fn drop(&mut self) {
        let len = self.incoming.len();
        secure_wipe(self.incoming.as_mut_ptr(), len);
        let len = self.plain.len();
        secure_wipe(self.plain.as_mut_ptr(), len);
        wipe_vec(&mut self.plain);
        wipe_vec(&mut self.outgoing);
    }
}

/// Zeroes a vector's whole allocation (not only its length) and empties it.
fn wipe_vec(v: &mut Vec<u8>) {
    secure_wipe(v.as_mut_ptr(), v.capacity());
    v.clear();
}
