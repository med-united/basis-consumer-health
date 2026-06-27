package de.servicehealtherx.quarkus.sicct.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Arrays;

/**
 * Seals and unseals ShS.KT.AUT to TPM 2.0 PCR 0/1/7 using tss.java (Microsoft TSS for Java).
 * Reference: FR-139. No tpm2-tools CLI in production.
 */
@ApplicationScoped
public class TpmSealer {

    private static final Logger LOG = Logger.getLogger(TpmSealer.class);

    private static final int[] PCR_INDICES = {0, 1, 7};
    private volatile boolean tpmAvailable = false;

    /**
     * 1-byte format marker prefixed to the value stored in {@code CardTerminal.sealedSharedSecret}
     * so {@link #recover(byte[])} knows how to interpret it independently of the TPM's current
     * availability (a TPM may be present when sealing but gone — or vice versa — when recovering).
     */
    private static final byte FORMAT_RAW = 0x00;
    private static final byte FORMAT_TPM_SEALED = 0x01;

    public void initialize() {
        try {
            // tss.java TPM 2.0 initialization — attempt to connect to TPM device
            // Microsoft TSS library: com.microsoft.tss.Tpm
            // If TPM is unavailable, terminals with sealed secrets stay FAILED per FR-139
            // Placeholder: real implementation requires tss.java TPM session setup
            tpmAvailable = false;
            LOG.warnf("[TPM] TPM 2.0 device not available — terminals with sealed secrets will remain FAILED");
        } catch (Exception e) {
            tpmAvailable = false;
            LOG.warnf("[TPM] TPM initialization failed: %s", e.getMessage());
        }
    }

    /**
     * Seals the shared secret to TPM PCR 0/1/7.
     * Zeros the raw secret from JVM heap immediately after sealing.
     *
     * @param secret raw shared secret (zeroed after sealing)
     * @return sealed blob to store in CardTerminal.sealedSharedSecret
     */
    public byte[] seal(byte[] secret) {
        if (!tpmAvailable) {
            throw new IllegalStateException("TPM 2.0 not available — cannot seal ShS.KT.AUT");
        }
        try {
            // tss.java TPM2B_SENSITIVE sealing to PCR policy
            // Placeholder: real implementation requires TPM session management
            byte[] blob = new byte[0];
            return blob;
        } finally {
            Arrays.fill(secret, (byte) 0);
            LOG.debugf("[TPM] raw ShS.KT.AUT zeroed from JVM heap after sealing");
        }
    }

    /**
     * Unseals the shared secret from TPM PCR 0/1/7.
     * Caller MUST zero the returned array immediately after use.
     *
     * @param sealedBlob from CardTerminal.sealedSharedSecret
     * @return raw shared secret (caller responsibility to zero after use)
     */
    public byte[] unseal(byte[] sealedBlob) {
        if (!tpmAvailable) {
            throw new IllegalStateException("TPM 2.0 not available — cannot unseal ShS.KT.AUT");
        }
        try {
            // tss.java TPM2_Unseal with PCR policy check
            // PCR mismatch → TPM_RC_POLICY_FAIL → caller should log CRITICAL + set terminal FAILED
            return new byte[0];
        } catch (Exception e) {
            LOG.errorf(e, "[TPM] unseal failed — PCR policy mismatch or TPM error");
            throw new RuntimeException("TPM unseal failed: " + e.getMessage(), e);
        }
    }

    /**
     * Produces the value to persist in {@code CardTerminal.sealedSharedSecret} for a freshly
     * paired terminal. When a TPM 2.0 device is available the raw ShS.KT.AUT is sealed to
     * PCR 0/1/7 (FR-139); otherwise it is stored unsealed (dev/test fallback — FR-139 not
     * satisfied). The result is self-describing via a 1-byte {@code FORMAT_*} marker so
     * {@link #recover(byte[])} can decode it regardless of the TPM state at recovery time.
     * The raw secret is always zeroed from the JVM heap before returning.
     *
     * @param rawSecret the raw shared secret (zeroed on return); {@code null} yields {@code null}
     * @return the marked blob to store, or {@code null} if {@code rawSecret} was {@code null}
     */
    public byte[] protect(byte[] rawSecret) {
        if (rawSecret == null) {
            return null;
        }
        try {
            if (tpmAvailable) {
                // seal() zeroes rawSecret itself; prefix the returned sealed blob.
                return prefix(FORMAT_TPM_SEALED, seal(rawSecret));
            }
            LOG.warnf("[TPM] TPM 2.0 unavailable — storing ShS.KT.AUT unsealed (dev/test fallback, FR-139 not satisfied)");
            return prefix(FORMAT_RAW, rawSecret);
        } finally {
            Arrays.fill(rawSecret, (byte) 0);
        }
    }

    /**
     * Recovers the raw ShS.KT.AUT from a value previously produced by {@link #protect(byte[])}.
     * The caller MUST zero the returned array immediately after use.
     *
     * @param storedBlob the marked blob from {@code CardTerminal.sealedSharedSecret}
     * @return the raw shared secret (caller owns and must zero it), or {@code null} for empty input
     */
    public byte[] recover(byte[] storedBlob) {
        if (storedBlob == null || storedBlob.length == 0) {
            return null;
        }
        byte format = storedBlob[0];
        byte[] payload = Arrays.copyOfRange(storedBlob, 1, storedBlob.length);
        switch (format) {
            case FORMAT_TPM_SEALED:
                try {
                    return unseal(payload);
                } finally {
                    Arrays.fill(payload, (byte) 0);
                }
            case FORMAT_RAW:
                return payload;
            default:
                Arrays.fill(payload, (byte) 0);
                throw new IllegalStateException("Unknown sealedSharedSecret format marker: " + format);
        }
    }

    private static byte[] prefix(byte marker, byte[] payload) {
        byte[] out = new byte[payload.length + 1];
        out[0] = marker;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    public boolean isTpmAvailable() {
        return tpmAvailable;
    }
}
