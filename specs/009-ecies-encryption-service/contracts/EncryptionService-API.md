# Contract: EncryptionService API

**Module**: `crypto-services-lib` · `de.servicehealtherx.crypto.services.EncryptionService`

The existing public method signatures are unchanged; only behaviour changes (placeholder → TI-ECIES). Records `EncryptRequest` / `DecryptRequest` are reused (see [data-model.md](../data-model.md)).

## `byte[] encryptDocument(EncryptRequest request)`

**Preconditions**
- `recipientCerts` is non-empty; every cert is EC and passes `TrustService.verify(cert, false)`.
- Each cert's public key is on a supported, named-curve-encoded curve.

**Behaviour**
1. Validate recipients (trust + EC + curve). Any failure → reject whole request, no output (FR-010).
2. Generate random 256-bit transport key; AES-256-GCM encrypt `document` (fresh 12-byte IV).
3. For each recipient: generate ephemeral EC key pair on the cert's curve; ELC-wrap the transport key → `(PO,C,T)`; build a `ktri` with `keyEncryptionAlgorithm = 1.2.276.0.76.4.222` and `encryptedKey = (PO,C,T)`.
4. Assemble CMS `AuthEnvelopedData` (DER) with all `ktri` + the single `authEncryptedContentInfo` + `mac`.
5. Audit success (operation, `ECIES-AES256-GCM`, recipientAlias, callerIdentity, timing). Return DER bytes.

**Postconditions / guarantees**
- Output parses as gematik-conformant `AuthEnvelopedData`; each recipient independently decrypts to the identical document (SC-001, SC-008).
- Two calls with identical inputs produce different output (fresh keys) (SC-005).

**Errors** (RuntimeException, audited as failure; no secret material in message)
| Condition | Reason |
|---|---|
| Empty recipient list | `no recipient` |
| RSA / non-EC recipient | `unsuitable recipient` |
| Unsupported / explicit-param curve | `unsupported curve` |
| Trust verification fails | `recipient certificate rejected` |
| Document exceeds max size | `document too large` |

## `byte[] decryptDocument(DecryptRequest request)`

**Preconditions**: `ciphertext` is DER `AuthEnvelopedData`; `decryptAlias` resolves to an available private key (software or card).

**Behaviour**
1. Parse `AuthEnvelopedData`; reject malformed/truncated (FR-011).
2. Select the `ktri` whose `rid` matches the certificate of `decryptAlias`; if none → `no matching key`, no plaintext (FR-007).
3. Verify `keyEncryptionAlgorithm == 1.2.276.0.76.4.222`, else `unsupported algorithm`.
4. `Cipher c = Cipher.getInstance("ELC", ElcSecurityProvider)` ; `c.init(DECRYPT_MODE, privateKey)` ; `transportKey = c.doFinal(encryptedKey)` — software or card transparently (see [ELC-Cipher-transformation.md](ELC-Cipher-transformation.md)).
5. AES-256-GCM decrypt `encryptedContent` with `mac`; GCM tag failure → `integrity failure`, no plaintext.
6. Audit success; zeroize transport key; return plaintext.

**Errors** (distinguishable reasons, FR-011): `unsupported algorithm` · `malformed input` · `no matching key` · `integrity failure`.

## Invariants
- `EncryptionService` references only JCE + Bouncy Castle types; it never imports smart-card / APDU / provider-routing types (FR-015).
- No transport key, ephemeral private key, or plaintext is logged or placed in audit records (FR-012).
