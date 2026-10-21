//! The WebUI's TLS certificate, and the local health check.
//!
//! Certificate: `TLS_CERT_FILE` + `TLS_KEY_FILE` (PEM; e.g. a reverse
//! proxy's or an internal CA's), else a self-signed one generated once and
//! kept in the config directory, so browsers see the same certificate after
//! a restart or a container re-creation (with the directory on a volume).

use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

/// Self-signed certificate and key in the config directory.
pub const CERT_FILE: &str = "tls-cert.pem";
pub const KEY_FILE: &str = "tls-key.pem";

/// Where the certificate and key come from.
#[derive(Debug, PartialEq, Eq)]
pub enum CertSource {
    /// `TLS_CERT_FILE` / `TLS_KEY_FILE`.
    Provided { cert: PathBuf, key: PathBuf },
    /// Self-signed, in the config directory (generated when missing).
    SelfSigned { cert: PathBuf, key: PathBuf },
}

impl CertSource {
    /// From the environment: both variables or neither.
    pub fn from_env(config_dir: &Path) -> Result<Self, String> {
        let var = |k| std::env::var_os(k).filter(|v| !v.is_empty()).map(PathBuf::from);
        match (var("TLS_CERT_FILE"), var("TLS_KEY_FILE")) {
            (Some(cert), Some(key)) => Ok(Self::Provided { cert, key }),
            (None, None) => Ok(Self::SelfSigned { cert: config_dir.join(CERT_FILE), key: config_dir.join(KEY_FILE) }),
            _ => Err("TLS_CERT_FILE 和 TLS_KEY_FILE 需要同时设置".into()),
        }
    }

    /// The PEM files to load, generating the self-signed pair if either is
    /// missing.
    pub fn ensure(&self) -> Result<(&Path, &Path), String> {
        match self {
            Self::Provided { cert, key } => Ok((cert, key)),
            Self::SelfSigned { cert, key } => {
                if !cert.exists() || !key.exists() {
                    let (c, k) = self_signed_pem()?;
                    write_private(key, k.as_bytes())?;
                    std::fs::write(cert, c).map_err(|e| format!("写入 {} 失败: {e}", cert.display()))?;
                    tracing::info!("已生成自签名证书 {}", cert.display());
                }
                Ok((cert, key))
            }
        }
    }
}

fn self_signed_pem() -> Result<(String, String), String> {
    let sans = vec!["localhost".to_string(), "127.0.0.1".to_string(), "::1".to_string()];
    let cert = rcgen::generate_simple_self_signed(sans).map_err(|e| format!("生成证书失败: {e}"))?;
    let pem = cert.serialize_pem().map_err(|e| format!("生成证书失败: {e}"))?;
    Ok((pem, cert.serialize_private_key_pem()))
}

/// Write a file readable only by its owner (created 0600, never wider).
fn write_private(path: &Path, data: &[u8]) -> Result<(), String> {
    let err = |e: std::io::Error| format!("写入 {} 失败: {e}", path.display());
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    opts.open(path).and_then(|mut f| f.write_all(data)).map_err(err)
}

// ── Health check ─────────────────────────────────────────────────────────────

/// The address a local health check connects to: the listen address, with
/// an unspecified one (`0.0.0.0` / `::`) replaced by loopback.
pub fn local_addr(bind: SocketAddr) -> SocketAddr {
    let ip = match bind.ip() {
        IpAddr::V4(ip) if ip.is_unspecified() => IpAddr::V4(Ipv4Addr::LOCALHOST),
        IpAddr::V6(ip) if ip.is_unspecified() => IpAddr::V6(Ipv6Addr::LOCALHOST),
        ip => ip,
    };
    SocketAddr::new(ip, bind.port())
}

/// `GET /healthz` over TLS; `Ok` when the WebUI answers 200.  The
/// certificate is not checked against a CA (the check targets our own
/// listener, often with a self-signed certificate, and sends nothing
/// secret), but its handshake signature is.
pub fn healthcheck(addr: SocketAddr) -> Result<(), String> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AnyCertificate(provider)))
        .with_no_client_auth();
    let name = rustls::pki_types::ServerName::IpAddress(addr.ip().into());
    let conn = rustls::ClientConnection::new(Arc::new(config), name).map_err(|e| e.to_string())?;

    let timeout = Duration::from_secs(5);
    let tcp = TcpStream::connect_timeout(&addr, timeout).map_err(|e| format!("{addr}: {e}"))?;
    tcp.set_read_timeout(Some(timeout)).map_err(|e| e.to_string())?;
    tcp.set_write_timeout(Some(timeout)).map_err(|e| e.to_string())?;
    let mut tls = rustls::StreamOwned::new(conn, tcp);
    tls.write_all(b"GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .map_err(|e| format!("{addr}: {e}"))?;
    let mut head = [0u8; 12];
    tls.read_exact(&mut head).map_err(|e| format!("{addr}: {e}"))?;
    if head.starts_with(b"HTTP/1.1 200") {
        Ok(())
    } else {
        Err(format!("{addr}: {}", String::from_utf8_lossy(&head).trim()))
    }
}

/// Accepts any certificate; verifies handshake signatures.
#[derive(Debug)]
struct AnyCertificate(Arc<rustls::crypto::CryptoProvider>);

impl rustls::client::danger::ServerCertVerifier for AnyCertificate {
    fn verify_server_cert(
        &self,
        _end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unspecified_listen_address_is_checked_on_loopback() {
        assert_eq!(local_addr("0.0.0.0:18080".parse().unwrap()), "127.0.0.1:18080".parse().unwrap());
        assert_eq!(local_addr("[::]:18080".parse().unwrap()), "[::1]:18080".parse().unwrap());
        assert_eq!(local_addr("192.168.1.2:443".parse().unwrap()), "192.168.1.2:443".parse().unwrap());
    }

    #[test]
    fn self_signed_certificate_is_generated_once_with_a_private_key() {
        let dir = std::env::temp_dir().join(format!("msst-webui-tls-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let src = CertSource::SelfSigned { cert: dir.join(CERT_FILE), key: dir.join(KEY_FILE) };
        let (cert, key) = src.ensure().unwrap();
        let first = std::fs::read_to_string(cert).unwrap();
        assert!(first.starts_with("-----BEGIN CERTIFICATE-----"));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(key).unwrap().permissions().mode() & 0o777, 0o600);
        }
        src.ensure().unwrap();
        assert_eq!(std::fs::read_to_string(cert).unwrap(), first, "kept across restarts");
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
