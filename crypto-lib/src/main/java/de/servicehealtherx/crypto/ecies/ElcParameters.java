package de.servicehealtherx.crypto.ecies;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;

import java.util.Set;

/**
 * Parameter set for the gemSpec_COS ELC scheme used to wrap the transport key
 * (gemSpec_COS N004.500 {@code ELC_ENC} / N001.520 {@code KeyDerivation_AES256}, BSI TR-03111).
 *
 * <p>The primitive is: ephemeral ECKA-DH on the recipient curve → X9.63 key derivation with
 * SHA-256 yielding {@code (Kenc, Kmac, T2)} → {@code C = AES-256-CBC(Kenc, IV=AES_ENC(Kenc,T2),
 * PaddingIso(M))} → {@code T = AES-CMAC(Kmac, C)}. The curve is taken from the recipient
 * certificate and MUST be one of the brainpool curves the TI cards support for ELC; explicit-
 * parameter curve encodings are rejected (A_23511).
 */
public final class ElcParameters {

    /** Curves a TI card / COS supports for ELC (gemSpec_COS); brainpoolP256r1 is the §4.7 example. */
    public static final Set<ASN1ObjectIdentifier> SUPPORTED_CURVES = Set.of(
            EciesOids.BRAINPOOL_P256R1,
            EciesOids.BRAINPOOL_P384R1,
            EciesOids.BRAINPOOL_P512R1);

    /** JCA/BC curve names keyed by OID, for {@code ECGenParameterSpec}. */
    public static String curveName(ASN1ObjectIdentifier curveOid) {
        if (EciesOids.BRAINPOOL_P256R1.equals(curveOid)) {
            return "brainpoolP256r1";
        }
        if (EciesOids.BRAINPOOL_P384R1.equals(curveOid)) {
            return "brainpoolP384r1";
        }
        if (EciesOids.BRAINPOOL_P512R1.equals(curveOid)) {
            return "brainpoolP512r1";
        }
        throw new IllegalArgumentException("Unsupported ELC curve: " + curveOid);
    }

    public static boolean isSupportedCurve(ASN1ObjectIdentifier curveOid) {
        return SUPPORTED_CURVES.contains(curveOid);
    }

    private ElcParameters() {
    }
}
