//! AES-GCM from the host: in the mod, the JDK's `AES/GCM/NoPadding`, which
//! HotSpot runs with AES-NI and CLMUL. In wasm, RustCrypto's AES is bitsliced
//! software, and in Chicory it was a third of a data channel's CPU time
//! (bench/webrtc/README.md).
//!
//! dimpl's own cipher suites are kept for everything but the record cipher:
//! key lengths, hashes, and DTLS 1.3's record number mask (one AES block per
//! record, cheap enough in software).
//!
//! The host module `sculk` provides, all lengths in bytes:
//!
//! - `aes_gcm_new(key, key_len) -> handle` (negative on failure)
//! - `aes_gcm_seal(handle, nonce12, aad, aad_len, buf, len) -> status`:
//!   encrypts `buf[..len]` in place and writes the 16-byte tag after it
//! - `aes_gcm_open(handle, nonce12, aad, aad_len, buf, len) -> status`:
//!   checks the tag in `buf[len-16..len]` and decrypts `buf[..len-16]` in place
//! - `aes_gcm_free(handle)`
//!
//! Status 0 is success. On failure `open` leaves no plaintext behind.

use std::sync::OnceLock;

use dimpl::crypto::{
    Aad, Buf, Cipher, Dtls12CipherSuite, Dtls13CipherSuite, HashAlgorithm, Nonce,
    SupportedDtls12CipherSuite, SupportedDtls13CipherSuite, TmpBuf,
};
use dimpl::{CryptoError, CryptoOperation};

const TAG: usize = 16;

#[link(wasm_import_module = "sculk")]
unsafe extern "C" {
    fn aes_gcm_new(key: *const u8, key_len: usize) -> i32;
    fn aes_gcm_free(handle: i32);
    fn aes_gcm_seal(
        handle: i32,
        nonce: *const u8,
        aad: *const u8,
        aad_len: usize,
        buf: *mut u8,
        len: usize,
    ) -> i32;
    fn aes_gcm_open(
        handle: i32,
        nonce: *const u8,
        aad: *const u8,
        aad_len: usize,
        buf: *mut u8,
        len: usize,
    ) -> i32;
}

#[derive(Debug)]
struct HostGcm(i32);

impl HostGcm {
    fn new(key: &[u8]) -> Result<Box<dyn Cipher>, CryptoError> {
        // SAFETY: the host reads key_len bytes at key.
        let handle = unsafe { aes_gcm_new(key.as_ptr(), key.len()) };
        if handle < 0 {
            return Err(CryptoError::OperationFailed(CryptoOperation::CreateCipher));
        }
        Ok(Box::new(HostGcm(handle)))
    }
}

impl Drop for HostGcm {
    fn drop(&mut self) {
        // SAFETY: the handle came from aes_gcm_new and is freed once.
        unsafe { aes_gcm_free(self.0) }
    }
}

impl Cipher for HostGcm {
    fn encrypt(&mut self, data: &mut Buf, aad: Aad, nonce: Nonce) -> Result<(), CryptoError> {
        let len = data.len();
        data.resize(len + TAG, 0);
        // SAFETY: buf has len + 16 bytes; nonce is 12; aad is aad.len().
        let status = unsafe {
            aes_gcm_seal(self.0, nonce.0.as_ptr(), aad.as_ptr(), aad.len(), data.as_mut_ptr(), len)
        };
        if status != 0 {
            data.resize(len, 0);
            return Err(CryptoError::OperationFailed(CryptoOperation::Encrypt));
        }
        Ok(())
    }

    fn decrypt(&mut self, data: &mut TmpBuf, aad: Aad, nonce: Nonce) -> Result<(), CryptoError> {
        let len = data.len();
        if len < TAG {
            return Err(CryptoError::CiphertextTooShort { minimum: TAG as u8, actual: len as u8 });
        }
        let buf = data.as_mut();
        // SAFETY: buf has len bytes; nonce is 12; aad is aad.len().
        let status = unsafe {
            aes_gcm_open(self.0, nonce.0.as_ptr(), aad.as_ptr(), aad.len(), buf.as_mut_ptr(), len)
        };
        if status != 0 {
            return Err(CryptoError::OperationFailed(CryptoOperation::Decrypt));
        }
        data.truncate(len - TAG);
        Ok(())
    }
}

/// A dimpl DTLS 1.2 suite whose record cipher is the host's.
#[derive(Debug)]
struct Host12(&'static dyn SupportedDtls12CipherSuite);

impl SupportedDtls12CipherSuite for Host12 {
    fn suite(&self) -> Dtls12CipherSuite {
        self.0.suite()
    }
    fn hash_algorithm(&self) -> HashAlgorithm {
        self.0.hash_algorithm()
    }
    fn key_lengths(&self) -> (usize, usize, usize) {
        self.0.key_lengths()
    }
    fn explicit_nonce_len(&self) -> usize {
        self.0.explicit_nonce_len()
    }
    fn tag_len(&self) -> usize {
        self.0.tag_len()
    }
    fn min_protected_fragment_len(&self) -> usize {
        self.0.min_protected_fragment_len()
    }
    fn create_cipher(&self, key: &[u8]) -> Result<Box<dyn Cipher>, CryptoError> {
        HostGcm::new(key)
    }
}

/// A dimpl DTLS 1.3 suite whose record cipher is the host's.
#[derive(Debug)]
struct Host13(&'static dyn SupportedDtls13CipherSuite);

impl SupportedDtls13CipherSuite for Host13 {
    fn suite(&self) -> Dtls13CipherSuite {
        self.0.suite()
    }
    fn hash_algorithm(&self) -> HashAlgorithm {
        self.0.hash_algorithm()
    }
    fn key_len(&self) -> usize {
        self.0.key_len()
    }
    fn iv_len(&self) -> usize {
        self.0.iv_len()
    }
    fn tag_len(&self) -> usize {
        self.0.tag_len()
    }
    fn min_protected_fragment_len(&self) -> usize {
        self.0.min_protected_fragment_len()
    }
    fn create_cipher(&self, key: &[u8]) -> Result<Box<dyn Cipher>, CryptoError> {
        HostGcm::new(key)
    }
    fn encrypt_sn(&self, sn_key: &[u8], sample: &[u8; 16]) -> [u8; 16] {
        self.0.encrypt_sn(sn_key, sample)
    }
}

/// dimpl's RustCrypto provider with AES-GCM from the host; other suites as they were.
pub fn dimpl_provider() -> dimpl::crypto::CryptoProvider {
    static SUITES12: OnceLock<&'static [&'static dyn SupportedDtls12CipherSuite]> = OnceLock::new();
    static SUITES13: OnceLock<&'static [&'static dyn SupportedDtls13CipherSuite]> = OnceLock::new();

    let mut p = dimpl::crypto::rust_crypto::default_provider();
    let base12 = p.cipher_suites;
    let base13 = p.dtls13_cipher_suites;
    p.cipher_suites = SUITES12.get_or_init(|| {
        let v: Vec<&'static dyn SupportedDtls12CipherSuite> = base12
            .iter()
            .map(|&s| match s.suite() {
                Dtls12CipherSuite::ECDHE_ECDSA_AES128_GCM_SHA256
                | Dtls12CipherSuite::ECDHE_ECDSA_AES256_GCM_SHA384 => {
                    &*Box::leak(Box::new(Host12(s))) as &'static dyn SupportedDtls12CipherSuite
                }
                _ => s,
            })
            .collect();
        Box::leak(v.into_boxed_slice())
    });
    p.dtls13_cipher_suites = SUITES13.get_or_init(|| {
        let v: Vec<&'static dyn SupportedDtls13CipherSuite> = base13
            .iter()
            .map(|&s| match s.suite() {
                Dtls13CipherSuite::AES_128_GCM_SHA256 | Dtls13CipherSuite::AES_256_GCM_SHA384 => {
                    &*Box::leak(Box::new(Host13(s))) as &'static dyn SupportedDtls13CipherSuite
                }
                _ => s,
            })
            .collect();
        Box::leak(v.into_boxed_slice())
    });
    p
}
