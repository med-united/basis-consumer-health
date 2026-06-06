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

    public boolean isTpmAvailable() {
        return tpmAvailable;
    }
}
