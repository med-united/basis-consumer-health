package de.servicehealtherx.konnektor.vsdm;

/**
 * Normalised local-ReadVSD request (the SOAP {@code ReadVSD} mapped to internal fields).
 *
 * @param ehcHandle          eGK card handle
 * @param hpcHandle          HBA or SMC-B card handle (for C2C)
 * @param performOnlineCheck must be {@code false} — {@code true} is rejected (FR-007)
 * @param readOnlineReceipt  must be {@code false} — {@code true} is rejected (FR-008)
 * @param mandantId          tenant id (required)
 * @param clientSystemId     client-system id (required)
 * @param workplaceId        workplace id (required)
 * @param userId             user id (required only when {@code hpcHandle} is an HBA)
 */
public record ReadVsdRequest(
        String ehcHandle,
        String hpcHandle,
        boolean performOnlineCheck,
        boolean readOnlineReceipt,
        String mandantId,
        String clientSystemId,
        String workplaceId,
        String userId) {
}
