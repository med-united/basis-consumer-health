package de.servicehealtherx.crypto.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AuditLogger {

    private static final Logger LOG = Logger.getLogger("AUDIT");

    public record AuditEntry(
        String alias,
        String operationType,
        String algorithm,
        String callerIdentity,
        boolean success,
        long durationMs,
        String errorDetail
    ) {}

    public void log(AuditEntry entry) {
        // Private key material MUST NOT appear in any logged field
        LOG.infof("[AUDIT] event=crypto_operation alias=%s operationType=%s algorithm=%s caller=%s success=%b duration=%dms%s",
            entry.alias(),
            entry.operationType(),
            entry.algorithm(),
            entry.callerIdentity(),
            entry.success(),
            entry.durationMs(),
            entry.errorDetail() != null ? " error=" + entry.errorDetail() : "");
    }

    public void logSuccess(String alias, String operationType, String algorithm, String callerIdentity, long durationMs) {
        log(new AuditEntry(alias, operationType, algorithm, callerIdentity, true, durationMs, null));
    }

    public void logFailure(String alias, String operationType, String algorithm, String callerIdentity, long durationMs, String errorDetail) {
        log(new AuditEntry(alias, operationType, algorithm, callerIdentity, false, durationMs, errorDetail));
    }
}
