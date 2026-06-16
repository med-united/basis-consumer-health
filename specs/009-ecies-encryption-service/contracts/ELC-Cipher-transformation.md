# Contract: JCE `Cipher("ELC")` transformation (decryption seam)

**Module**: `crypto-lib` · `de.servicehealtherx.crypto.ecies.jce`

Implements the clarified design: a standard JDK `Provider` + `CipherSpi` so callers decrypt the ECIES transport-key cryptogram through `javax.crypto.Cipher`, unaware of whether the key is software- or card-resident (FR-015, Principle VIII).

## Provider registration
- `ElcSecurityProvider extends java.security.Provider` registers service `Cipher.ELC` → `ElcCipherSpi`.
- Registered programmatically (CDI startup / static init) — not installed globally in `java.security` to avoid side effects on other modules.

## Usage
```java
Cipher c = Cipher.getInstance("ELC", elcSecurityProvider);
c.init(Cipher.DECRYPT_MODE, privateKey);      // ECPrivateKey or CardElcPrivateKey
byte[] transportKey = c.doFinal(elcCryptogramDer);   // input = (PO,C,T) DER; output = 32-byte AES key
```

## `ElcCipherSpi` behaviour
| Aspect | Contract |
|---|---|
| Supported mode | DECRYPT_MODE only; ENCRYPT_MODE → `UnsupportedOperationException` (encryption uses `ElcKeyWrapper` directly, no card needed) |
| Input | DER-encoded `(PO,C,T)` ELC cryptogram |
| Output | the unwrapped 256-bit transport key |
| Key = `ECPrivateKey` | dispatch to `SoftwareElcDecryptor` (Bouncy Castle: ECKA → X9.63-KDF/SHA-256 → AES-CBC decrypt → CMAC verify) |
| Key = `CardElcPrivateKey` | dispatch to that key's `CardElcDecryptor` → `PSO:DECIPHER` APDUs (gemSpec_COS §6.8.2.3); card returns the transport key |
| CMAC / curve-point invalid | throw `BadPaddingException`/`AEADBadTagException`-style failure → surfaced as `integrity failure` / `malformed input` |
| `engineUpdate`, `engineSetMode`, `engineWrap`, `engineUnwrap`, `engineGetIV` (unused) | `UnsupportedOperationException` with descriptive message (Principle VIII "throw, never stub") |

## `ElcDecryptor` seam (internal)
```java
interface ElcDecryptor {            // two real implementations → justifies the abstraction (Principle I)
    byte[] unwrapTransportKey(ElcCryptogram cryptogram);
}
```
- `SoftwareElcDecryptor(ECPrivateKey)` — in `crypto-lib`.
- `CardElcDecryptor(KeyAlias, <card transport>)` — in `crypto-pcsc-lib` / `crypto-sicct-lib`; MAY reuse the existing card-transport plumbing.

## Guarantees
- The same `(PO,C,T)` bytes decrypt identically on-card and in software (Decision 2/3) — a card and a P12 holding the same key yield the same transport key.
- No card/APDU type crosses into `EncryptionService`; the only shared surface is `javax.crypto.Cipher` + `java.security.PrivateKey`.
