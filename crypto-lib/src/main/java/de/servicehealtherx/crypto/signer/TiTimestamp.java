package de.servicehealtherx.crypto.signer;

/**
 * Fetches an RFC 3161 timestamp token from the Telematik Infrastructure TSA.
 * The timestamp covers the raw signature value and is embedded as an unsigned
 * CAdES attribute (id-aa-signatureTimeStampToken, OID 1.2.840.113549.1.9.16.2.14).
 */
public class TiTimestamp {

    private TiTimestamp() {
    }

    /**
     * Requests a timestamp token for the given signature bytes from the TI TSA.
     *
     * @param signatureBytes raw signature value to be timestamped
     * @return DER-encoded TimeStampToken (RFC 3161 § 2.4.2)
     * @throws Exception if the TSA is unreachable or returns an error
     */
    public static byte[] fetchTimestamp(byte[] signatureBytes) throws Exception {
        throw new UnsupportedOperationException("TI TSA endpoint not yet configured");
    }
}
