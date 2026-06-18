package de.servicehealtherx.apdu.model;

/**
 * Certificate reference for the Kartendienst read-certificate path (gemSpec_Kon ReadCardCertificate,
 * TAB_KON_858). Identifies <em>which</em> certificate object on a card is addressed; the concrete
 * card file is then a function of this reference, the card type and the crypto algorithm
 * ({@code RSA} / {@code ECC}).
 *
 * <p>Only the references the system exposes are modelled here: {@link #C_AUT} (authentication, on
 * HBA and SMC-B) and {@link #C_SIG} (the SMC-B organisation signature, {@code C.HCI.OSIG}). The
 * gemSpec references {@code C.ENC} and {@code C.QES} are not read through this path.
 */
public enum CertificateRef {

    /** Authentication certificate — {@code C.HP.AUT} (HBA) / {@code C.HCI.AUT} (SMC-B). */
    C_AUT("C.AUT"),

    /** Organisation-signature certificate — {@code C.HCI.OSIG} (SMC-B only). */
    C_SIG("C.SIG");

    private final String id;

    CertificateRef(String id) {
        this.id = id;
    }

    /** The gemSpec identifier ({@code C.AUT} / {@code C.SIG}). */
    public String id() {
        return id;
    }

    /** Resolve from a gemSpec identifier; accepts the {@code C.OSIG} synonym for {@code C.SIG}. */
    public static CertificateRef fromId(String id) {
        if (id == null) {
            throw new IllegalArgumentException("certRef must not be null");
        }
        return switch (id.toUpperCase()) {
            case "C.AUT" -> C_AUT;
            case "C.SIG", "C.OSIG" -> C_SIG;
            default -> throw new IllegalArgumentException("Unsupported certificate reference: " + id);
        };
    }
}
