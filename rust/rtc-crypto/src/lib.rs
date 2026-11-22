//! A str0m [`CryptoProvider`] in pure Rust, for the WebAssembly build.
//!
//! This is str0m-rust-crypto 0.7.0 with two changes: the DTLS certificate is
//! made in [`cert`] rather than by dimpl's `rcgen` feature, which depends on
//! aws-lc-rs (C) and so does not build for `wasm32-wasip1`; and in wasm, DTLS
//! records can be sealed with the host's AES-GCM ([`Aes::Host`], host_aes.rs).

mod cert;
mod dtls;
#[cfg(target_arch = "wasm32")]
mod host_aes;
mod sha1;
mod sha256;
mod srtp;

use dtls::RustCryptoDtlsProvider;
use sha1::{RustCryptoSha1HmacProvider, RustCryptoSha1Provider};
use sha256::RustCryptoSha256Provider;
use srtp::RustCryptoSrtpProvider;
use str0m_proto::crypto::CryptoProvider;

/// Where DTLS records' AES-GCM runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Aes {
    /// RustCrypto, in software.
    Rust,
    /// The host's `sculk.aes_gcm_*` imports (wasm only; see host_aes.rs).
    Host,
}

/// The provider: DTLS through dimpl, everything else through RustCrypto.
///
/// # Panics
///
/// With [`Aes::Host`] outside wasm, where there is no host to ask.
pub fn provider(aes: Aes) -> CryptoProvider {
    static SRTP: RustCryptoSrtpProvider = RustCryptoSrtpProvider;
    static SHA1_HMAC: RustCryptoSha1HmacProvider = RustCryptoSha1HmacProvider;
    static SHA1: RustCryptoSha1Provider = RustCryptoSha1Provider;
    static SHA256: RustCryptoSha256Provider = RustCryptoSha256Provider;
    static DTLS_RUST: RustCryptoDtlsProvider = RustCryptoDtlsProvider { host_aes: false };
    static DTLS_HOST: RustCryptoDtlsProvider = RustCryptoDtlsProvider { host_aes: true };
    assert!(
        aes == Aes::Rust || cfg!(target_arch = "wasm32"),
        "Aes::Host needs the wasm host"
    );
    let dtls = if aes == Aes::Host { &DTLS_HOST } else { &DTLS_RUST };

    CryptoProvider {
        srtp_provider: &SRTP,
        sha1_hmac_provider: &SHA1_HMAC,
        sha1_provider: &SHA1,
        sha256_provider: &SHA256,
        dtls_provider: dtls,
    }
}
