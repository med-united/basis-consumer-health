package de.servicehealtherx.konnektor.vsdm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * Writes the data-access audit entry to the eGK's {@code EF.Logging} after a protected-data read
 * (TUC_KON_006 / FM_VSDM Tab_FM_VSDM_06 "Lesen der geschützten VSD"). The acting party is the
 * SMC-B/HBA used for the C2C (FM_VSDM §4.2 Actor-ID = its ICCSN) — no extra card read is needed
 * since the {@link CardObject} already carries the ICCSN.
 *
 * <p>Per FR-023 / VSDM-A_2654 a failure to write the audit aborts the operation and returns no VSD.
 * {@code EF.Logging} (MF/DF.HCA, FID {@code D006}, SFID {@code 06}) is a <em>cyclic</em> EF with a
 * fixed {@value #RECORD_LENGTH}-octet record length, writable in the role-authenticated state
 * ({@code flagTI.32}) the C2C handshake establishes — in <strong>plaintext</strong>, not Secure
 * Messaging (gemSpec_eGK_ObjSys §5.4.3). The {@code APPEND RECORD} therefore self-selects the file by
 * SFID in P2 and carries the record un-wrapped ({@link ApduSecureChannel#NONE}).
 */
public final class EgkAuditWriter {

    /** Short EF identifier of MF/DF.HCA/EF.Logging; APPEND RECORD selects it via P2 = SFID &lt;&lt; 3. */
    private static final int SFI_LOGGING = 0x06;

    /** Fixed record length of the cyclic EF.Logging (gemSpec_eGK_ObjSys Tab_eGK_ObjSys_036). */
    private static final int RECORD_LENGTH = 46;

    public void writeReadProtectedVsd(CardReaderPort port, int slot, CardObject actor, ApduSecureChannel channel)
            throws CardTransportException {
        CommandAPDU append = channel.wrap(new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_APPEND_RECORD, 0x00, SFI_LOGGING << 3,
                auditRecord(actor)));
        ResponseAPDU resp = channel.unwrap(port.transmit(slot, append));
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "writing the eGK access-audit log failed");
        }
    }

    /**
     * The fixed {@value #RECORD_LENGTH}-octet audit record. The cyclic EF requires the data length to
     * equal the record length (a shorter record is rejected with 6700). Field layout follows
     * gemSpec_eGK_Fach_VSDM and is opaque to the COS; we record the read-access marker and the actor's
     * ICCSN (FM_VSDM §4.2), zero-padded to the record length.
     */
    private static byte[] auditRecord(CardObject actor) {
        String actorId = actor.iccsn() != null ? actor.iccsn()
                : (actor.cardHolderName() != null ? actor.cardHolderName() : actor.cardHandle());
        byte[] content = ("R;" + actorId).getBytes(StandardCharsets.UTF_8); // Type-of-Access 'R' + Actor-ID
        return Arrays.copyOf(content, RECORD_LENGTH); // truncate/zero-pad to the fixed record length
    }
}
