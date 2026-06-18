# Contract: CETP mTLS (konnektor as TLS client)

**Component**: `de.servicehealtherx.cetp.tls.CetpTlsContextFactory`.
**Source**: `gemSpec_Kon_V5.27.0` TIP1-A_4595, TIP1-A_5009, A_21760-02, TAB_KON_852
(CETP1/CETP2).

## Configuration variants (TAB_KON_852)

| Variant | `cetp.tls.mandatory` | Behaviour |
|---|---|---|
| **CETP1** | `true` (default) | Deliver over TLS; konnektor initiates as TLS client; presents client cert **iff** the sink requests client auth (FR-024/FR-025). |
| **CETP2** | `false` | Deliver over plain TCP; no `SslHandler` in the pipeline (FR-027). |

## Client identity (FR-025, A_21760-02)

- Reuse `SmkCSAKAut` (injected; produced by `SmkCSAKAutProvider` in
  `quarkus-sicct-extension`). Its `getKeyManagerFactory()` wraps the **C.AK.AUT**
  key material (HSM via SunPKCS11, P12 fallback for test).
- Build with `SslContextBuilder.forClient().keyManager(smkCSAKAut.getKeyManagerFactory())`
  so the client certificate is presented only when the server requests it.

## Server-cert trust (FR-026, TIP1-A_5009)

- Build a `TrustManagerFactory` with the **PKIX** algorithm:
  `TrustManagerFactory.getInstance("PKIX")` initialised from the configured
  **client-system trust store** (`cetp.tls.truststore.*`).
- The resulting `X509TrustManager` performs PKIX certification-path validation of the
  client system's TLS **server** certificate. No valid path → handshake aborts →
  delivery refused → counts as one failed attempt (FR-028).
- This is **distinct from** TI-PKI trust (`TucPki18VerifierTrustManager` / gemLibPki is
  NOT used here) — client systems are validated against an operator-managed trust
  store, not the TI trust space (research.md D3).

## Provider & ciphers

- Provider: `BouncyCastleJsseProvider` (consistent with `KonnektorSslHandler`).
- Cipher suites: `GematikSSLConfig.CIPHER_SUITE_LIST`; protocol per `GematikSSLConfig`.

## Resulting Netty pipeline (CETP1)

```
[ SslHandler(clientContext) ] → [ frame writer (CetpFrameCodec) ] → channel
```
For CETP2 the `SslHandler` is omitted.

## Test obligations

- `test_TIP1_A_4595_delivery_over_tls_when_mandatory()`
- `test_A_21760_02_presents_smkcsakaut_client_cert_on_request()`
- `test_TIP1_A_5009_untrusted_server_cert_refused_via_pkix()`
- `test_FR_027_plain_tcp_when_tls_not_mandatory()`
- `test_FR_028_tls_handshake_failure_counts_as_failed_attempt()`
