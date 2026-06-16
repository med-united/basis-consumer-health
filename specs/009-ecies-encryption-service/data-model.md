# Data Model: ECIES Transport Encryption

**Feature**: specs/009-ecies-encryption-service · **Date**: 2026-06-15

All structures are transient (in-memory / on-the-wire). Nothing is persisted. See [research.md](research.md) for normative sources and [contracts/ecies-asn1.md](contracts/ecies-asn1.md) for byte-level ASN.1.

## Runtime entities (Java)

### EncryptRequest (existing record, reused)
| Field | Type | Notes |
|---|---|---|
| recipientAlias | `KeyAlias` | logical recipient reference (audit) |
| recipientCerts | `List<X509Certificate>` | **1..N** EC recipient certificates (FR-001); empty → reject (FR-010) |
| document | `byte[]` | plaintext; may be empty; bounded by max-size guard |
| documentType | `String` | metadata; not bound into ciphertext |
| callerIdentity | `String` | audit |
| eccPreferred | `boolean` | ECIES path requires EC certs; RSA cert → unsuitable-recipient error |

### DecryptRequest (existing record, reused)
| Field | Type | Notes |
|---|---|---|
| decryptAlias | `KeyAlias` | caller-supplied key selecting the recipient entry (Clarification) |
| ciphertext | `byte[]` | DER-encoded CMS `AuthEnvelopedData` |
| callerIdentity | `String` | audit |

### TransportKey (transient)
256-bit AES key, freshly random per encryption (FR-004). Encrypts the document via AES-256-GCM; ECIES-wrapped per recipient. Zeroized after use (FR-012).

### EphemeralKeyPair (transient)
EC key pair on the recipient's curve, fresh **per recipient per encryption** (FR-005). Public part → `PO`; private part discarded/zeroized immediately after key derivation.

### ElcCryptogram (the `(PO, C, T)` tuple)
| Field | Type | Meaning |
|---|---|---|
| curveOid | `ASN1ObjectIdentifier` | named curve of `PO` (e.g. brainpoolP256r1 `1.3.36.3.3.2.8.1.1.7`) |
| po | `byte[]` | ephemeral public point, uncompressed `04‖X‖Y` |
| c | `byte[]` | AES-256-CBC ciphertext of the transport key |
| t | `byte[]` | AES-CMAC tag (8 bytes) |

Codec: `ElcCryptogram.toAsn1()` / `ElcCryptogram.parse(byte[])`.

### CardElcPrivateKey (`implements java.security.PrivateKey`)
| Field | Type | Notes |
|---|---|---|
| alias | `KeyAlias` | card key reference |
| decryptor | `ElcDecryptor` (card) | transport handle; **no key bytes** (`getEncoded()` → null, `getFormat()` → null) |

Software keys use a standard `ECPrivateKey`; the `ElcCipherSpi` branches on this type vs `CardElcPrivateKey`.

## Wire structures (ASN.1 / CMS)

```
ContentInfo
 └─ AuthEnvelopedData                      -- id-ct-authEnvelopedData 1.2.840.113549.1.9.16.1.23
     ├─ version: 0
     ├─ recipientInfos: SET OF RecipientInfo        -- one per recipient (1..N)
     │   └─ ktri (KeyTransRecipientInfo)
     │       ├─ version: 0
     │       ├─ rid: issuerAndSerialNumber          -- selects entry by recipient cert
     │       ├─ keyEncryptionAlgorithm: oid_ti_ecies_transport_encryption  -- 1.2.276.0.76.4.222
     │       └─ encryptedKey: OCTET STRING          -- the (PO,C,T) card-compatible blob
     ├─ authEncryptedContentInfo
     │   ├─ contentType: id-data 1.2.840.113549.1.7.1
     │   ├─ contentEncryptionAlgorithm: id-aes256-gcm 2.16.840.1.101.3.4.1.46
     │   │   └─ params: { aes-nonce (12-byte IV), aes-ICVlen (16) }
     │   └─ encryptedContent: OCTET STRING          -- AES-256-GCM(document)
     └─ mac: OCTET STRING                           -- 16-byte GCM tag
```

`(PO, C, T)` blob (the `encryptedKey`):
```
[6] { curveOid OID ; [APPLICATION 73] { [6] PO } ; [6] C ; [14] T }
```

## Relationships
- One `AuthEnvelopedData` → one `TransportKey` → one `encryptedContent`/`mac`.
- One `AuthEnvelopedData` → **N** `ktri`, each → one `ElcCryptogram` → one `EphemeralKeyPair`.
- One `ElcCryptogram` ↔ one recipient certificate (curve, `rid`).

## Validation rules (from FRs)
| Rule | Source |
|---|---|
| ≥ 1 recipient; all EC; all trust-verified, else reject whole request | FR-001, FR-010 |
| Recipient/ephemeral curve ∈ {brainpoolP256r1/384r1/512r1}; named-curve encoding | FR-009, A_23511 |
| Each `keyEncryptionAlgorithm` = `1.2.276.0.76.4.222` | FR-002 |
| Fresh transport key + fresh ephemeral key per recipient per call | FR-004, FR-005 |
| Decrypt: alias must match a `ktri` (`rid`), else "no matching key", no plaintext | FR-007 |
| Decrypt reject: unsupported OID / malformed / integrity failure, distinct reasons | FR-011 |
| No secret/plaintext in logs or audit | FR-012 |

## State / lifecycle
No persistent entity has a multi-state lifecycle (encrypt and decrypt are single-shot transformations); transient keys follow create → use → zeroize. A PlantUML state chart is therefore omitted (justified in `diagrams/README.md`).
