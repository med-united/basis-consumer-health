package de.servicehealtherx.quarkus.mtls;

/**
 * JMX management interface for the self-managed mTLS PKI. Exposed on the platform MBean server
 * (visible in Hawtio) so an operator can issue client certificates against the auto-generated
 * self-signed root CA without shell access to the certificate files.
 */
public interface MtlsCertManagementMBean {

    /**
     * @return the root CA certificate, PEM-encoded — distribute this to clients so they trust the
     *         server certificate, and to anyone who needs to verify certificates this CA issued.
     */
    String getRootCertificatePem();

    /**
     * Issue a certificate signed by the root CA from a PEM-encoded PKCS#10 CSR. The CSR's subject
     * and public key are honoured; the client keeps its private key.
     *
     * @param csrPem       PEM CSR ({@code -----BEGIN CERTIFICATE REQUEST-----})
     * @param validityDays validity period in days
     * @return the issued certificate, PEM-encoded
     */
    String signCsr(String csrPem, int validityDays);

    /**
     * Mint a complete identity — generate a fresh key pair, issue a CA-signed certificate for
     * {@code subjectDn}, and return it as a password-protected PKCS#12 (private key + cert chain).
     *
     * @param subjectDn    leaf subject DN, e.g. {@code "CN=client-system-1, O=ServiceHealtheRX, C=DE"}
     * @param password     PKCS#12 password
     * @param validityDays validity period in days
     * @return the PKCS#12 keystore bytes
     */
    byte[] createP12(String subjectDn, String password, int validityDays);

    /**
     * Like {@link #createP12}, but writes the PKCS#12 to {@code <certsDir>/mtls/issued/} and returns
     * the file path. Convenient from JMX consoles that cannot display a {@code byte[]} result.
     *
     * @return the absolute path of the written {@code .p12} file
     */
    String createP12File(String subjectDn, String password, int validityDays);
}
