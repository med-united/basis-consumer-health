package de.servicehealtherx.crypto.services;

import de.gematik.pki.gemlibpki.commons.certificate.Admission;
import de.servicehealtherx.crypto.CryptoProvider;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.TrustService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@ApplicationScoped
public class CertificateService {

    private static final Logger LOG = Logger.getLogger(CertificateService.class);

    @Inject
    Instance<CryptoProvider> cryptoProviders;

    @Inject
    TrustService trustService;

    @Inject
    AuditLogger auditLogger;

    public enum CertRef {
        C_AUT, C_OSIG
    }

    public enum CryptAlgorithm {
        ECC, RSA
    }

    public record ReadCertRequest(
            KeyAlias alias,
            CertRef certRef,
            CryptAlgorithm crypt,
            String callerIdentity) {
    }

    public record VerifyCertResult(
            String result,
            String detail,
            List<String> roles) {
    }

    /**
     * Reads an X.509 certificate from the key source addressed by the request
     * (gemSpec_Kon ReadCardCertificate / TUC_KON_216 "LeseZertifikat").
     *
     * @return the DER-encoded certificate
     */
    public byte[] readCertificate(ReadCertRequest request) {
        long start = System.currentTimeMillis();
        String certRefName = request.certRef().name();
        try {
            LOG.infof("[CertificateService] readCertificate alias=%s certRef=%s crypt=%s",
                    request.alias(), request.certRef(), request.crypt());

            CryptoProvider provider = resolveProvider(request.alias());
            X509Certificate cert = provider.readCertificate(
                    request.alias(),
                    toCertRefId(request.certRef()),
                    request.crypt().name());
            if (cert == null) {
                throw new IllegalStateException(
                        request.crypt() + " certificate " + certRefName + " not present on " + request.alias());
            }

            byte[] der = cert.getEncoded();
            auditLogger.logSuccess(request.alias().value(), "READ_CERT",
                    certRefName, request.callerIdentity(), System.currentTimeMillis() - start);
            return der;
        } catch (Exception e) {
            auditLogger.logFailure(request.alias().value(), "READ_CERT",
                    certRefName, request.callerIdentity(), System.currentTimeMillis() - start,
                    e.getMessage());
            throw new RuntimeException("readCertificate failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read the C.AUT certificate of an inserted card addressed by its gematik CardHandle, delegating
     * to whichever {@link CryptoProvider} currently holds that card (PC/SC or SICCT). READ BINARY on
     * the certificate file is access condition ALWAYS, so no PIN is required.
     */
    public byte[] readCardCertificate(String cardHandle, String callerIdentity) {
        long start = System.currentTimeMillis();
        try {
            byte[] certDer = providerForCard(cardHandle).readCardCertificate(cardHandle);
            auditLogger.logSuccess(cardHandle, "READ_CERT", "C.AUT", callerIdentity,
                    System.currentTimeMillis() - start);
            return certDer;
        } catch (Exception e) {
            auditLogger.logFailure(cardHandle, "READ_CERT", "C.AUT", callerIdentity,
                    System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("readCardCertificate failed: " + e.getMessage(), e);
        }
    }

    private CryptoProvider providerForCard(String cardHandle) {
        for (CryptoProvider provider : cryptoProviders) {
            if (provider.ownsCard(cardHandle)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("No card found for handle: " + cardHandle);
    }

    /**
     * Checks the status of a certificate against the TI trust store
     * (gemSpec_Kon VerifyCertificate / TUC_KON_037 "Zertifikat prüfen").
     *
     * <p>The result is one of {@code VALID}, {@code INVALID} or {@code INCONCLUSIVE}, accompanied by the
     * profession-role OIDs carried in the certificate's Admission extension.
     */
    public VerifyCertResult verifyCertificate(X509Certificate certificate, String callerIdentity) {
        long start = System.currentTimeMillis();
        List<String> roles = extractRoles(certificate);
        try {
            TrustService.VerificationResult vr = trustService.verify(certificate, false);
            String result = vr.valid() ? "VALID" : "INVALID";
            auditLogger.logSuccess("verify", "VERIFY_CERT", "X509",
                    callerIdentity, System.currentTimeMillis() - start);
            return new VerifyCertResult(result, vr.detail(), roles);
        } catch (Exception e) {
            auditLogger.logFailure("verify", "VERIFY_CERT", "X509",
                    callerIdentity, System.currentTimeMillis() - start, e.getMessage());
            return new VerifyCertResult("INCONCLUSIVE", e.getMessage(), roles);
        }
    }

    /**
     * Selects the {@link CryptoProvider} that currently owns the given alias. The provider for the
     * alias' source type reports {@link KeyStoreAvailability#AVAILABLE} for it.
     */
    private CryptoProvider resolveProvider(KeyAlias alias) {
        CryptoProvider fallback = null;
        for (CryptoProvider provider : cryptoProviders) {
            KeyStoreAvailability availability = provider.getAvailability(alias);
            if (availability == KeyStoreAvailability.AVAILABLE) {
                return provider;
            }
            if (availability != KeyStoreAvailability.UNAVAILABLE) {
                fallback = provider;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("No available key source for alias: " + alias.value());
    }

    /** Maps the internal certificate reference to its gemSpec identifier (TAB_KON_858). */
    private static String toCertRefId(CertRef certRef) {
        return switch (certRef) {
            case C_AUT -> "C.AUT";
            case C_OSIG -> "C.SIG";
        };
    }

    /** Extracts the profession-role OIDs from the certificate's Admission extension (best effort). */
    private static List<String> extractRoles(X509Certificate certificate) {
        try {
            Set<String> oids = new Admission(certificate).getProfessionOids();
            if (oids == null || oids.isEmpty()) {
                return List.of();
            }
            return List.copyOf(new TreeSet<>(oids));
        } catch (Exception e) {
            // Absent or unreadable Admission extension is not an error — the cert simply carries no roles
            return List.of();
        }
    }
}
