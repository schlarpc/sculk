//! A self-signed ECDSA P-256 certificate for DTLS, without rcgen.
//!
//! WebRTC peers do not check the certificate's contents: they pin its
//! fingerprint, which travels in the SDP (and, for sculk, inside the Noise
//! session). It still has to be a well-formed X.509 certificate, with a
//! serial number that is random (Firefox rejects repeats across hosts).

use std::str::FromStr;
use std::time::Duration;

use der::Encode;
use p256::ecdsa::{DerSignature, SigningKey};
use p256::pkcs8::{EncodePrivateKey, EncodePublicKey};
use str0m_proto::crypto::dtls::DtlsCert;
use x509_cert::builder::profile::BuilderProfile;
use x509_cert::builder::{Builder, CertificateBuilder};
use x509_cert::certificate::TbsCertificate;
use x509_cert::ext::Extension;
use x509_cert::name::Name;
use x509_cert::serial_number::SerialNumber;
use x509_cert::spki::{SubjectPublicKeyInfoOwned, SubjectPublicKeyInfoRef};
use x509_cert::time::Validity;

#[derive(Debug)]
pub struct Error(#[allow(dead_code)] String);

fn err(e: impl std::fmt::Display) -> Error {
    Error(e.to_string())
}

/// Self-signed, `CN=sculk`, no extensions.
struct SelfSigned(Name);

impl BuilderProfile for SelfSigned {
    fn get_issuer(&self, subject: &Name) -> Name {
        subject.clone()
    }
    fn get_subject(&self) -> Name {
        self.0.clone()
    }
    fn build_extensions(
        &self,
        _: SubjectPublicKeyInfoRef<'_>,
        _: SubjectPublicKeyInfoRef<'_>,
        _: &TbsCertificate,
    ) -> x509_cert::builder::Result<Vec<Extension>> {
        Ok(Vec::new())
    }
}

fn key() -> Result<SigningKey, Error> {
    loop {
        let mut bytes = [0u8; 32];
        getrandom::fill(&mut bytes).map_err(err)?;
        // Out of range (zero, or not below the group order) is vanishingly rare.
        if let Ok(k) = SigningKey::from_slice(&bytes) {
            return Ok(k);
        }
    }
}

pub fn self_signed() -> Result<DtlsCert, Error> {
    let key = key()?;

    let mut serial = [0u8; 16];
    getrandom::fill(&mut serial).map_err(err)?;
    serial[0] &= 0x7f; // positive
    serial[0] |= 0x01; // and without a leading zero byte

    let spki_der = key.verifying_key().to_public_key_der().map_err(err)?;
    let spki = SubjectPublicKeyInfoOwned::try_from(spki_der.as_bytes()).map_err(err)?;
    let cert = CertificateBuilder::new(
        SelfSigned(Name::from_str("CN=sculk").map_err(err)?),
        SerialNumber::new(&serial).map_err(err)?,
        Validity::from_now(Duration::from_secs(365 * 24 * 60 * 60)).map_err(err)?,
        spki,
    )
    .map_err(err)?
    .build::<_, DerSignature>(&key)
    .map_err(err)?;

    Ok(DtlsCert {
        certificate: cert.to_der().map_err(err)?,
        private_key: key.to_pkcs8_der().map_err(err)?.as_bytes().to_vec(),
    })
}
