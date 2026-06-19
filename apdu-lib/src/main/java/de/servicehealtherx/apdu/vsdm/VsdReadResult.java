package de.servicehealtherx.apdu.vsdm;

import java.util.Optional;

/**
 * The result of a local ReadVSD: the raw (gzip-compressed, byte-for-byte) VSDM container payloads
 * plus the converted status. {@code protectedData} (EF.GVD) is present only when the card-to-card
 * authentication authorised it; otherwise PD, VD and status are still returned (FR-021).
 *
 * <p>Short-lived and never logged or persisted (FR-028): the SOAP edge Base64-encodes the payloads
 * into the response and then this object is eligible for GC.
 */
public record VsdReadResult(
        byte[] personalData,
        byte[] generalData,
        Optional<byte[]> protectedData,
        VsdStatus status) {
}
