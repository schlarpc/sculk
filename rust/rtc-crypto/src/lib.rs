//! A str0m [`CryptoProvider`] in pure Rust, for the WebAssembly build.
//!
//! This is str0m-rust-crypto 0.7.0 with one change: the DTLS certificate is
//! made in [`cert`] rather than by dimpl's `rcgen` feature, which depends on
//! aws-lc-rs (C) and so does not build for `wasm32-wasip1`. Keeping the
//! provider here is also where JDK crypto through host imports would go, if
//! RustCrypto inside wasm turns out too slow (see bench/rtc).

mod cert;
mod dtls;
mod sha1;
mod sha256;
mod srtp;

use dtls::RustCryptoDtlsProvider;
use sha1::{RustCryptoSha1HmacProvider, RustCryptoSha1Provider};
use sha256::RustCryptoSha256Provider;
use srtp::RustCryptoSrtpProvider;
use str0m_proto::crypto::CryptoProvider;

/// The provider: DTLS through dimpl, everything else through RustCrypto.
pub fn provider() -> CryptoProvider {
    static SRTP: RustCryptoSrtpProvider = RustCryptoSrtpProvider;
    static SHA1_HMAC: RustCryptoSha1HmacProvider = RustCryptoSha1HmacProvider;
    static SHA1: RustCryptoSha1Provider = RustCryptoSha1Provider;
    static SHA256: RustCryptoSha256Provider = RustCryptoSha256Provider;
    static DTLS: RustCryptoDtlsProvider = RustCryptoDtlsProvider;

    CryptoProvider {
        srtp_provider: &SRTP,
        sha1_hmac_provider: &SHA1_HMAC,
        sha1_provider: &SHA1,
        sha256_provider: &SHA256,
        dtls_provider: &DTLS,
    }
}
