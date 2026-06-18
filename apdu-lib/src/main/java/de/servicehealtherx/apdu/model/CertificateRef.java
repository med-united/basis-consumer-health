package de.servicehealtherx.apdu.model;

/**
 * Certificate reference for the Kartendienst read-certificate path (gemSpec_Kon ReadCardCertificate,
 * TAB_KON_858). Identifies <em>which</em> certificate object on a card is addressed; the concrete
 * card file is then a function of this reference, the card type and the crypto algorithm
 * ({@code RSA} / {@code ECC}).
 *
 * <p>References modelled here: {@link #C_AUT} (authentication, on HBA and SMC-B), {@link #C_SIG}
 * (the SMC-B organisation signature, {@code C.HCI.OSIG}), and — for the HBA — {@link #C_ENC}
 * (encryption, {@code C.HP.ENC}) and {@link #C_QES} (qualified signature, {@code C.HP.QES}, which
 * lives in {@code DF.QES} rather than {@code DF.ESIGN}).
 */
public enum CertificateRef {

    /** Authentication certificate — {@code C.HP.AUT} (HBA) / {@code C.HCI.AUT} (SMC-B). */
    C_AUT("C.AUT"),

    /** Encryption certificate — {@code C.HP.ENC} (HBA), in {@code DF.ESIGN}. */
    C_ENC("C.ENC"),

    /** Qualified-signature certificate — {@code C.HP.QES} (HBA), in {@code DF.QES}. */
    C_QES("C.QES"),

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
            case "C.ENC" -> C_ENC;
            case "C.QES" -> C_QES;
            case "C.SIG", "C.OSIG" -> C_SIG;
            default -> throw new IllegalArgumentException("Unsupported certificate reference: " + id);
        };
    }
}
