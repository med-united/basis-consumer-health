package de.servicehealtherx.crypto.ecies;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;

/**
 * Object identifiers used by the gematik TI-ECIES transport-encryption scheme
 * (gemSpec_Krypt §4.7, gemSpec_OID). See {@code contracts/ecies-asn1.md}.
 */
public final class EciesOids {

    private EciesOids() {
    }

    /** gematik {@code oid_ti_ecies_transport_encryption} — the per-recipient keyEncryptionAlgorithm. */
    public static final ASN1ObjectIdentifier TI_ECIES_TRANSPORT_ENCRYPTION =
            new ASN1ObjectIdentifier("1.2.276.0.76.4.222");

    /** CMS {@code id-ct-authEnvelopedData} (RFC 5083). */
    public static final ASN1ObjectIdentifier ID_CT_AUTH_ENVELOPED_DATA =
            new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.1.23");

    /** CMS {@code id-data}. */
    public static final ASN1ObjectIdentifier ID_DATA =
            new ASN1ObjectIdentifier("1.2.840.113549.1.7.1");

    /** {@code id-aes256-gcm} (RFC 5084) — content encryption. */
    public static final ASN1ObjectIdentifier ID_AES256_GCM =
            new ASN1ObjectIdentifier("2.16.840.1.101.3.4.1.46");

    public static final ASN1ObjectIdentifier BRAINPOOL_P256R1 =
            new ASN1ObjectIdentifier("1.3.36.3.3.2.8.1.1.7");
    public static final ASN1ObjectIdentifier BRAINPOOL_P384R1 =
            new ASN1ObjectIdentifier("1.3.36.3.3.2.8.1.1.11");
    public static final ASN1ObjectIdentifier BRAINPOOL_P512R1 =
            new ASN1ObjectIdentifier("1.3.36.3.3.2.8.1.1.13");
}
