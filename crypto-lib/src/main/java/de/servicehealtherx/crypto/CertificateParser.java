package de.servicehealtherx.crypto;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.cert.X509Certificate;

@ApplicationScoped
public class CertificateParser {

    public record CertificateInfo(
        String registrationNumber,
        String cardType,
        boolean hasQesKeyUsage,
        boolean hasAuthKeyUsage
    ) {}

    public CertificateInfo parse(X509Certificate certificate) {
        String subject = certificate.getSubjectX500Principal().getName();
        String regNr = extractOid(subject, "1.2.276.0.76.4.203");
        String cardType = determineCardType(certificate);
        boolean qes = hasExtendedKeyUsage(certificate, "1.2.840.113549.1.9.15.5");
        boolean auth = hasExtendedKeyUsage(certificate, "1.3.6.1.5.5.7.3.2");
        return new CertificateInfo(regNr, cardType, qes, auth);
    }

    private String extractOid(String subject, String oid) {
        // OID extraction from subject distinguished name
        for (String part : subject.split(",")) {
            part = part.trim();
            if (part.startsWith(oid + "=")) {
                return part.substring(oid.length() + 1);
            }
        }
        return null;
    }

    private String determineCardType(X509Certificate cert) {
        String issuer = cert.getIssuerX500Principal().getName();
        if (issuer.contains("HBA") || issuer.contains("eHBA")) return "eHBA";
        if (issuer.contains("SMC-B") || issuer.contains("SMCB")) return "SMC-B";
        return "UNKNOWN";
    }

    private boolean hasExtendedKeyUsage(X509Certificate cert, String oid) {
        try {
            var ekus = cert.getExtendedKeyUsage();
            return ekus != null && ekus.contains(oid);
        } catch (Exception e) {
            return false;
        }
    }
}
