# Contract: ECIES ASN.1 wire format

**Normative**: gemSpec_Krypt §4.7 / A_17220; gemSpec_COS §6.8.1.4, §6.8.2.3, (N085.068); RFC 5083/5084/5652.

The output of `encryptDocument` and the input of `decryptDocument` is a DER-encoded CMS `ContentInfo` wrapping `AuthEnvelopedData`.

## OIDs (fixed)
| Name | OID |
|---|---|
| `id-ct-authEnvelopedData` | 1.2.840.113549.1.9.16.1.23 |
| `id-data` | 1.2.840.113549.1.7.1 |
| `id-aes256-gcm` | 2.16.840.1.101.3.4.1.46 |
| **`oid_ti_ecies_transport_encryption`** | **1.2.276.0.76.4.222** |
| brainpoolP256r1 | 1.3.36.3.3.2.8.1.1.7 |
| brainpoolP384r1 | 1.3.36.3.3.2.8.1.1.11 |
| brainpoolP512r1 | 1.3.36.3.3.2.8.1.1.13 |

## Envelope (per data-model.md)
```
ContentInfo { contentType id-ct-authEnvelopedData; content AuthEnvelopedData }
AuthEnvelopedData {
  version 0,
  recipientInfos SET OF { ktri { version 0, rid issuerAndSerialNumber,
                                 keyEncryptionAlgorithm oid_ti_ecies_transport_encryption,
                                 encryptedKey OCTET STRING <-- (PO,C,T) } },   -- 1..N
  authEncryptedContentInfo { contentType id-data,
                             contentEncryptionAlgorithm id-aes256-gcm { nonce(12B), icvLen(16) },
                             encryptedContent },
  mac OCTET STRING(16)            -- AES-GCM tag
}
```

## `encryptedKey` — card-compatible `(PO, C, T)` (the §4.7 example, brainpoolP256r1)
```
0 142: [6] {
3   9:   OBJECT IDENTIFIER brainpoolP256r1 (1 3 36 3 3 2 8 1 1 7)
14 67:   [APPLICATION 73] {
17 65:     [6] 04 || X(32) || Y(32)        -- PO, uncompressed ephemeral point (65 B)
         }
84 49:   [6] <C>                            -- AES-256-CBC ciphertext of transport key
135 8:   [14] <T>                           -- AES-CMAC tag (8 B)
       }
```
Tags: outer `[6]`=0xA6 (COS ELC cryptogram); `[APPLICATION 73]`=0x7F49 (public-key DO); `[14]`=0x8E (MAC). `C` length and CMAC truncation pinned to gemSpec_COS in `ElcCryptogramTest` against these example bytes.

## Conformance assertions (tests)
- `keyEncryptionAlgorithm` of every `ktri` equals `1.2.276.0.76.4.222` (SC-003).
- Structure validates field-by-field against this layout (SC-002, US3-AC3).
- N recipients ⇒ N `ktri`, one shared `encryptedContent`/`mac` (US1-AC3).
- Round-trip with the published gematik test vectors decrypts to expected plaintext (SC-002).
