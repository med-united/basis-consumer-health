# Quickstart: Validate ECIES Transport Encryption

**Feature**: specs/009-ecies-encryption-service. Proves the feature end-to-end. See [contracts/](contracts/) and [data-model.md](data-model.md) for details — not duplicated here.

## Prerequisites
- Java 21, Maven; repo built (`./mvnw -q -pl crypto-lib,crypto-services-lib -am install`).
- A brainpoolP256r1 test key pair + self-signed cert in a P12 (software recipient).
- (Optional) gematik test vectors (Beispiel-Chiffrate) for SC-002; otherwise the OpenSSL/BC reference below.

## Build & test
```bash
./mvnw -q -pl crypto-lib,crypto-services-lib -am test
```
Expected: `ElcCryptogramTest`, `EciesTransportEncryptionTest`, `ElcCipherSpiTest`, and the `EncryptionService` round-trip IT pass.

## Scenario 1 — Single-recipient round-trip (US1/US2, SC-001)
1. `encryptDocument` with one brainpoolP256r1 EC cert + a sample document.
2. Assert output parses as `AuthEnvelopedData`; its one `ktri.keyEncryptionAlgorithm == 1.2.276.0.76.4.222` (SC-003).
3. `decryptDocument` with the matching P12 alias → recovers the original bytes (SC-001).

## Scenario 2 — Multi-recipient (US1-AC3, SC-008)
1. `encryptDocument` with **three** EC certs.
2. Assert exactly three `ktri`, one shared `encryptedContent`/`mac`.
3. `decryptDocument` independently with each recipient's alias → each recovers the identical document; no recipient needs another's key.

## Scenario 3 — Card key via JCE seam (FR-015)
1. Register `ElcSecurityProvider`; resolve a `CardElcPrivateKey` (fake `CardElcDecryptor` test double standing in for `PSO:DECIPHER`).
2. `decryptDocument` with a `pcsc/...` alias → succeeds through the same code path as the P12 case.
3. Assert `EncryptionService` sources contain no `smartcardio`/APDU imports (FR-015).

## Scenario 4 — Negative cases (FR-011, SC-004)
| Input | Expected reason | Plaintext returned |
|---|---|---|
| Flip one byte of `encryptedContent` | `integrity failure` | none |
| `keyEncryptionAlgorithm` → other OID | `unsupported algorithm` | none |
| Truncate the DER | `malformed input` | none |
| Decrypt with an alias not among recipients | `no matching key` | none |
| Encrypt with empty recipient list | `no recipient` | (no output) |
| Encrypt to an RSA cert | `unsuitable recipient` | (no output) |
| Encrypt to a non-brainpool / explicit-param curve | `unsupported curve` | (no output) |

## Scenario 5 — Cross-tool interoperability (US3, SC-002)
- **With gematik vectors**: `decryptDocument` the supplied ciphertext → expected plaintext.
- **Reference fallback**: decrypt the service's output with an independent BC/OpenSSL ELC reference; confirm identical plaintext and field-by-field structure match.

## Done
- [ ] Scenarios 1–5 pass · [ ] `keyEncryptionAlgorithm` is `1.2.276.0.76.4.222` on 100% of outputs · [ ] no secret material in logs/audit (grep test) · [ ] coverage ≥ 80% on the `ecies` package.
