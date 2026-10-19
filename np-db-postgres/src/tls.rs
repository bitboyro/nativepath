//! TLS for PostgreSQL connections: `sslmode` negotiation (SSLRequest), certificate verification
//! and the `tls-server-end-point` hash used for SCRAM channel binding.

use crate::{DbError, ErrorKind};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::WebPkiServerVerifier;
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use crate::tls_stream::TlsStream;
use rustls::client::UnbufferedClientConnection;
use rustls::{ClientConfig, DigitallySignedStruct, RootCertStore, SignatureScheme};
use sha2::Digest;
use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::path::Path;
use std::sync::Arc;

/// libpq's `sslmode`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum SslMode {
    /// Plaintext only.
    Disable = 0,
    /// Plaintext; TLS is not attempted (libpq would retry with TLS if the server refuses plaintext).
    Allow = 1,
    /// TLS if the server supports it, else plaintext. Certificates are not verified.
    Prefer = 2,
    /// TLS or fail. Certificates are verified only when a root certificate is configured (as libpq does).
    Require = 3,
    /// TLS, certificate chain verified against the root certificate; host name not checked.
    VerifyCa = 4,
    /// TLS, chain and host name verified.
    VerifyFull = 5,
}

impl SslMode {
    pub fn from_i32(v: i32) -> Option<SslMode> {
        Some(match v {
            0 => SslMode::Disable,
            1 => SslMode::Allow,
            2 => SslMode::Prefer,
            3 => SslMode::Require,
            4 => SslMode::VerifyCa,
            5 => SslMode::VerifyFull,
            _ => return None,
        })
    }
}

/// libpq's `channel_binding`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ChannelBinding {
    Disable = 0,
    /// Use SCRAM-SHA-256-PLUS when TLS is in use and the server offers it.
    Prefer = 1,
    /// Fail unless authentication used SCRAM-SHA-256-PLUS. Protects the login against a
    /// man in the middle even when certificates are not verified.
    Require = 2,
}

impl ChannelBinding {
    pub fn from_i32(v: i32) -> Option<ChannelBinding> {
        Some(match v {
            0 => ChannelBinding::Disable,
            1 => ChannelBinding::Prefer,
            2 => ChannelBinding::Require,
            _ => return None,
        })
    }
}

/// What a connection (and its cancel requests) needs to set up TLS.
#[derive(Clone)]
pub(crate) struct TlsSetup {
    pub mode: SslMode,
    pub config: Arc<ClientConfig>,
    pub server_name: ServerName<'static>,
}

impl TlsSetup {
    /// Builds the client configuration for `mode`, or `None` for modes that never use TLS.
    pub fn new(mode: SslMode, host: &str, root_cert: Option<&Path>) -> Result<Option<TlsSetup>, DbError> {
        if matches!(mode, SslMode::Disable | SslMode::Allow) {
            return Ok(None);
        }
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let roots = match root_cert {
            Some(path) => Some(load_roots(path)?),
            None => None,
        };
        let verifier: Arc<dyn ServerCertVerifier> = match (mode, roots) {
            (SslMode::VerifyCa | SslMode::VerifyFull, None) => {
                return Err(tls_error(format!("sslmode={} needs sslrootcert (the CA that signed the server certificate)",
                    if mode == SslMode::VerifyCa { "verify-ca" } else { "verify-full" })));
            }
            (SslMode::Prefer | SslMode::Require, None) => Arc::new(NoVerification(provider.clone())),
            (SslMode::VerifyFull, Some(roots)) => webpki(roots, &provider)?,
            // With a root certificate, require behaves like verify-ca, as in libpq.
            (_, Some(roots)) => Arc::new(ChainOnly(webpki(roots, &provider)?)),
            (SslMode::Disable | SslMode::Allow, None) => unreachable!(),
        };
        let config = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .map_err(|e| tls_error(e.to_string()))?
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        let server_name = ServerName::try_from(host.to_string())
            .or_else(|_| ServerName::try_from("postgres".to_string()))
            .map_err(|e| tls_error(e.to_string()))?;
        Ok(Some(TlsSetup { mode, config: Arc::new(config), server_name }))
    }

    /// Sends SSLRequest and, if the server agrees, performs the handshake. Returns the plain
    /// socket back when the server declines and the mode allows plaintext.
    pub fn negotiate(&self, mut sock: TcpStream) -> Result<Stream, DbError> {
        const SSL_REQUEST: [u8; 8] = [0, 0, 0, 8, 0x04, 0xd2, 0x16, 0x2f];
        sock.write_all(&SSL_REQUEST).map_err(DbError::io)?;
        // Exactly one byte, read straight from the socket: nothing the server sends before the
        // handshake may be buffered and later treated as encrypted data (CVE-2021-23214).
        let mut answer = [0u8; 1];
        sock.read_exact(&mut answer).map_err(DbError::io)?;
        match answer[0] {
            b'S' => {
                let conn = UnbufferedClientConnection::new(self.config.clone(), self.server_name.clone())
                    .map_err(|e| tls_error(e.to_string()))?;
                let tls = TlsStream::connect(conn, sock).map_err(|e| tls_error(format!("TLS handshake failed: {e}")))?;
                let end_point = tls.peer_certificates().and_then(|c| c.first()).and_then(|c| tls_server_end_point(c));
                Ok(Stream::Tls { tls: Box::new(tls), end_point })
            }
            b'N' if self.mode == SslMode::Prefer => Ok(Stream::Plain(sock)),
            b'N' => Err(DbError::new(ErrorKind::Io, "08001", "the server does not support TLS (sslmode requires it)")),
            _ => Err(DbError::protocol("unexpected answer to SSLRequest")),
        }
    }
}

fn tls_error(message: impl Into<String>) -> DbError {
    DbError::new(ErrorKind::Io, "08001", message)
}

fn load_roots(path: &Path) -> Result<RootCertStore, DbError> {
    let mut roots = RootCertStore::empty();
    let certs: Vec<CertificateDer<'static>> = CertificateDer::pem_file_iter(path)
        .map_err(|e| tls_error(format!("cannot read sslrootcert {}: {e}", path.display())))?
        .collect::<Result<_, _>>()
        .map_err(|e| tls_error(format!("invalid PEM in sslrootcert {}: {e}", path.display())))?;
    for c in certs {
        roots.add(c).map_err(|e| tls_error(format!("unusable certificate in sslrootcert: {e}")))?;
    }
    if roots.is_empty() {
        return Err(tls_error(format!("sslrootcert {} holds no certificates", path.display())));
    }
    Ok(roots)
}

fn webpki(roots: RootCertStore, provider: &Arc<CryptoProvider>) -> Result<Arc<WebPkiServerVerifier>, DbError> {
    WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider.clone())
        .build()
        .map_err(|e| tls_error(e.to_string()))
}

/// The socket under a connection: plaintext or TLS.
pub(crate) enum Stream {
    Plain(TcpStream),
    Tls {
        tls: Box<TlsStream>,
        /// `tls-server-end-point` channel-binding data, when the certificate's hash is known.
        end_point: Option<Vec<u8>>,
    },
}

impl Stream {
    pub fn socket(&self) -> &TcpStream {
        match self {
            Stream::Plain(s) => s,
            Stream::Tls { tls, .. } => &tls.sock,
        }
    }

    /// Zeroes the TLS layer's buffers (no-op for plaintext; the engine wipes its own).
    pub fn wipe(&mut self) {
        if let Stream::Tls { tls, .. } = self {
            tls.wipe();
        }
    }

    /// Sends a TLS close_notify alert (no-op for plaintext).
    pub fn close_notify(&mut self) {
        if let Stream::Tls { tls, .. } = self {
            tls.close_notify();
        }
    }

    pub fn is_tls(&self) -> bool {
        matches!(self, Stream::Tls { .. })
    }

    pub fn end_point(&self) -> Option<&[u8]> {
        match self {
            Stream::Tls { end_point, .. } => end_point.as_deref(),
            Stream::Plain(_) => None,
        }
    }
}

impl Read for Stream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Stream::Plain(s) => s.read(buf),
            Stream::Tls { tls, .. } => tls.read(buf),
        }
    }
}

impl Write for Stream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            Stream::Plain(s) => s.write(buf),
            Stream::Tls { tls, .. } => tls.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            Stream::Plain(s) => s.flush(),
            Stream::Tls { tls, .. } => tls.flush(),
        }
    }
}

/// `sslmode=prefer|require` without a root certificate: encrypt, but accept any certificate
/// (handshake signatures are still checked). Pair with `channel_binding=require` to keep the
/// login safe from a man in the middle.
#[derive(Debug)]
struct NoVerification(Arc<CryptoProvider>);

impl ServerCertVerifier for NoVerification {
    fn verify_server_cert(&self, _: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime)
        -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct)
        -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct)
        -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

/// `verify-ca`: the chain must lead to the configured root; the host name is not checked.
#[derive(Debug)]
struct ChainOnly(Arc<WebPkiServerVerifier>);

impl ServerCertVerifier for ChainOnly {
    fn verify_server_cert(&self, end_entity: &CertificateDer<'_>, intermediates: &[CertificateDer<'_>], name: &ServerName<'_>,
                          ocsp: &[u8], now: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        match self.0.verify_server_cert(end_entity, intermediates, name, ocsp, now) {
            Err(rustls::Error::InvalidCertificate(rustls::CertificateError::NotValidForName))
            | Err(rustls::Error::InvalidCertificate(rustls::CertificateError::NotValidForNameContext { .. })) => {
                Ok(ServerCertVerified::assertion())
            }
            other => other,
        }
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct)
        -> Result<HandshakeSignatureValid, rustls::Error> {
        self.0.verify_tls12_signature(message, cert, dss)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct)
        -> Result<HandshakeSignatureValid, rustls::Error> {
        self.0.verify_tls13_signature(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.supported_verify_schemes()
    }
}

/// RFC 5929 `tls-server-end-point`: the server certificate hashed with its signature's hash
/// function (SHA-256 when that is MD5 or SHA-1). `None` when the hash cannot be determined
/// (for example Ed25519 or RSA-PSS), in which case channel binding is not offered.
pub(crate) fn tls_server_end_point(cert: &CertificateDer<'_>) -> Option<Vec<u8>> {
    const SHA384_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0c];
    const SHA512_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0d];
    const SHA256_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b];
    const SHA1_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x05];
    const ECDSA_SHA256: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02];
    const ECDSA_SHA384: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x03];
    const ECDSA_SHA512: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x04];
    let der = cert.as_ref();
    let oid = signature_algorithm_oid(der)?;
    Some(match oid {
        SHA384_RSA | ECDSA_SHA384 => sha2::Sha384::digest(der).to_vec(),
        SHA512_RSA | ECDSA_SHA512 => sha2::Sha512::digest(der).to_vec(),
        SHA256_RSA | ECDSA_SHA256 | SHA1_RSA => sha2::Sha256::digest(der).to_vec(),
        _ => return None,
    })
}

/// Certificate ::= SEQUENCE { tbsCertificate SEQUENCE, signatureAlgorithm SEQUENCE { OID, ... }, ... }
fn signature_algorithm_oid(der: &[u8]) -> Option<&[u8]> {
    let (tag, cert, _) = tlv(der)?;
    if tag != 0x30 {
        return None;
    }
    let (_, _, after_tbs) = tlv(cert)?;
    let (tag, alg, _) = tlv(after_tbs)?;
    if tag != 0x30 {
        return None;
    }
    let (tag, oid, _) = tlv(alg)?;
    (tag == 0x06).then_some(oid)
}

/// One DER element: (tag, contents, rest).
fn tlv(b: &[u8]) -> Option<(u8, &[u8], &[u8])> {
    let tag = *b.first()?;
    let first = *b.get(1)? as usize;
    let (len, header) = if first < 0x80 {
        (first, 2)
    } else {
        let n = first & 0x7f;
        if n == 0 || n > 4 {
            return None;
        }
        let mut len = 0usize;
        for i in 0..n {
            len = (len << 8) | *b.get(2 + i)? as usize;
        }
        (len, 2 + n)
    };
    let end = header.checked_add(len)?;
    Some((tag, b.get(header..end)?, b.get(end..)?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256, PKCS_ECDSA_P384_SHA384, PKCS_ED25519};

    fn cert(alg: &'static rcgen::SignatureAlgorithm) -> CertificateDer<'static> {
        let key = KeyPair::generate_for(alg).unwrap();
        CertificateParams::new(vec!["db".to_string()]).unwrap().self_signed(&key).unwrap().der().clone()
    }

    #[test]
    fn end_point_hash_follows_the_signature_algorithm() {
        let p256 = cert(&PKCS_ECDSA_P256_SHA256);
        assert_eq!(tls_server_end_point(&p256).unwrap(), sha2::Sha256::digest(p256.as_ref()).to_vec());
        let p384 = cert(&PKCS_ECDSA_P384_SHA384);
        assert_eq!(tls_server_end_point(&p384).unwrap(), sha2::Sha384::digest(p384.as_ref()).to_vec());
        // RFC 5929 defines no hash for Ed25519; channel binding is then not used.
        assert!(tls_server_end_point(&cert(&PKCS_ED25519)).is_none());
    }

    #[test]
    fn der_parsing_rejects_garbage() {
        assert!(signature_algorithm_oid(&[]).is_none());
        assert!(signature_algorithm_oid(&[0x30, 0x82, 0xff]).is_none());
        assert!(signature_algorithm_oid(&[0x04, 0x00]).is_none());
    }

    #[test]
    fn modes_from_ffi_values() {
        assert_eq!(SslMode::from_i32(5), Some(SslMode::VerifyFull));
        assert_eq!(SslMode::from_i32(9), None);
        assert_eq!(ChannelBinding::from_i32(2), Some(ChannelBinding::Require));
    }
}
