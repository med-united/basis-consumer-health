package de.servicehealtherx.apdu.vsdm;

import java.time.OffsetDateTime;

/**
 * The VSD status read from EF.StatusVD and converted per gemSpec_FM_VSDM Tab_FM_VSDM_21.
 *
 * @param status    {@code "0"} = consistent / no open transactions, {@code "1"} = inconsistent
 * @param timestamp timestamp of the last VSD update
 * @param version   schema version, e.g. {@code 7.3.1}
 */
public record VsdStatus(String status, OffsetDateTime timestamp, String version) {

    public boolean isInconsistent() {
        return "1".equals(status);
    }
}
