package de.servicehealtherx.crypto;

import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import de.servicehealtherx.crypto.model.PinStatusResult;
import de.servicehealtherx.crypto.model.PinVerificationResult;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

public interface CryptoProvider {

    CryptoOperationResult sign(CryptoOperationRequest request);

    boolean verify(CryptoOperationRequest request, byte[] signature);

    CryptoOperationResult encrypt(CryptoOperationRequest request);

    CryptoOperationResult decrypt(CryptoOperationRequest request);

    /**
     * Reads the X.509 certificate referenced by {@code certRef} ({@code C.AUT} / {@code C.SIG} …)
     * for the given crypto algorithm ({@code RSA} / {@code ECC}) from the key source identified by
     * {@code alias} (gemSpec_Kon ReadCardCertificate, TUC_KON_216 "LeseZertifikat").
     *
     * <p>The default implementation rejects the call; providers backed by a real key source override it.
     */
    default X509Certificate readCertificate(KeyAlias alias, String certRef, String crypt) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support readCertificate for alias " + alias.value());
    }

    List<KeyStoreDescriptor> listKeyStores();

    KeyStoreAvailability getAvailability(KeyAlias alias);

    Map<String, KeyStoreAvailability> getAvailabilities();

    // ─── Card-handle addressed operations (smart-card providers) ──────────────────────────────
    // gematik addresses inserted cards by opaque CardHandle (GetCards), not by KeyAlias. These
    // default to "not mine / unsupported" so software providers (P12) need not implement them; the
    // PC/SC and SICCT providers override them to drive the inserted card by handle.

    /** Whether this provider currently holds the card identified by {@code cardHandle}. */
    default boolean ownsCard(String cardHandle) {
        return false;
    }

    /** Read the DER-encoded C.AUT certificate of the card (no PIN). */
    default byte[] readCardCertificate(String cardHandle) {
        throw new UnsupportedOperationException("readCardCertificate not supported by " + getClass().getSimpleName());
    }

    /**
     * Read the DER-encoded certificate addressed by {@code certRef} ({@code C.AUT} / {@code C.ENC} /
     * {@code C.QES} / {@code C.SIG}) and crypto algorithm ({@code RSA} / {@code ECC}) of the inserted
     * card identified by {@code cardHandle} (gemSpec_Kon ReadCardCertificate). READ BINARY on the
     * certificate files is access condition ALWAYS, so no PIN is required.
     */
    default byte[] readCardCertificate(String cardHandle, String certRef, String crypt) {
        throw new UnsupportedOperationException("readCardCertificate not supported by " + getClass().getSimpleName());
    }

    /** Sign {@code hash} with the card's C.AUT key (ExternalAuthenticate); returns the raw signature. */
    default byte[] externalAuthenticate(String cardHandle, byte[] hash) {
        throw new UnsupportedOperationException("externalAuthenticate not supported by " + getClass().getSimpleName());
    }

    /** Create a qualified signature over {@code data} with the card's C.QES key (SignDocument). */
    default byte[] signQes(String cardHandle, byte[] data) {
        throw new UnsupportedOperationException("signQes not supported by " + getClass().getSimpleName());
    }

    /**
     * VERIFY the {@code pinType} PIN (e.g. {@code PIN.SMC} on an SMC-B, {@code PIN.CH} on an HBA) of
     * the inserted card identified by {@code cardHandle} (gemSpec_Kon VerifyPin / TUC_KON_012). The
     * provider sources the PIN secret itself (no PIN pad in a software konnektor), so callers pass
     * only the PIN type, never the secret. Returns the card's verification outcome.
     */
    default PinVerificationResult verifyPin(String cardHandle, String pinType) {
        throw new UnsupportedOperationException("verifyPin not supported by " + getClass().getSimpleName());
    }

    /**
     * Read the status of the {@code pinType} PIN (e.g. {@code PIN.SMC} on an SMC-B, {@code PIN.CH} on
     * an HBA) of the inserted card identified by {@code cardHandle} (gemSpec_Kon GetPinStatus /
     * TUC_KON_011). Unlike {@link #verifyPin}, this presents no secret and does not consume a retry.
     * Returns the card's reported PIN state and remaining retry counter.
     */
    default PinStatusResult getPinStatus(String cardHandle, String pinType) {
        throw new UnsupportedOperationException("getPinStatus not supported by " + getClass().getSimpleName());
    }
}
