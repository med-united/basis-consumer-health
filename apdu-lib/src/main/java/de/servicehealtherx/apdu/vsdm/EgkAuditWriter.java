package de.servicehealtherx.apdu.vsdm;

import java.nio.charset.StandardCharsets;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Writes the data-access audit entry to the eGK's logging file after a protected-data read
 * (TUC_KON_006). The acting party (TUC_KON_034) is taken from the SMC-B/HBA used for C2C — no extra
 * card read is needed since the {@link CardObject} already carries the ICCSN / holder name.
 *
 * <p>Per FR-023 / VSDM-A_2654 a failure to write the audit aborts the operation and returns no VSD.
 * The append is Secure-Messaging-wrapped because the eGK logging file is only writable in the
 * AUT_VSD state established by the C2C handshake.
 */
public final class EgkAuditWriter {

    /** Short EF identifier of the eGK logging file (audit), used in APPEND RECORD P2. */
    private static final int SFI_LOGGING = 0x1E;

    public void writeReadProtectedVsd(CardReaderPort port, int slot, CardObject actor, ApduSecureChannel channel)
            throws CardTransportException {
        String actorId = actor.iccsn() != null ? actor.iccsn()
                : (actor.cardHolderName() != null ? actor.cardHolderName() : actor.cardHandle());
        byte[] record = ("VSD-GVD-READ;actor=" + actorId).getBytes(StandardCharsets.UTF_8);

        CommandAPDU append = channel.wrap(new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_APPEND_RECORD, 0x00, SFI_LOGGING << 3, record));
        ResponseAPDU resp = channel.unwrap(port.transmit(slot, append));
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "writing the eGK access-audit log failed");
        }
    }
}
