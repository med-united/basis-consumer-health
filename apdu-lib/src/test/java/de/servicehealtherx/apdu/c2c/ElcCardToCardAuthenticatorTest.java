package de.servicehealtherx.apdu.c2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.ScriptedCardReaderPort;
import de.servicehealtherx.apdu.model.CardType;

/**
 * Structural test of the host-side one-sided ELC role-authentication APDU sequence (TUC_KON_005
 * {@code einseitig}). The on-card cryptography is not exercised; the test scripts the eGK/SMC-B
 * responses and asserts the sequencing, the plaintext-read outcome ({@link ApduSecureChannel#NONE}),
 * and the PIN-not-verified mapping.
 */
class ElcCardToCardAuthenticatorTest {

    // Both cards share one ctid so the single ScriptedCardReaderPort sees the full interleaved sequence.
    private final UUID ctid = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (tag > 0xFF) {
            o.write(tag >> 8);
        }
        o.write(tag & 0xFF);
        o.write(v.length);
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    /** Minimal CVC: 7F21{ 7F4E{ 42=CAR(8), 5F20=CHR(12) }, 5F37=sig }. */
    private static byte[] cvc(byte[] car8, byte[] chr12) {
        byte[] body = concat(tlv(0x42, car8), tlv(0x5F20, chr12));
        return tlv(0x7F21, concat(tlv(0x7F4E, body), tlv(0x5F37, new byte[64])));
    }

    private static byte[] bytes(int len, int fill) {
        byte[] b = new byte[len];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    private CardReaderPortResolver resolver(ScriptedCardReaderPort port) {
        return id -> id.equals(ctid) ? Optional.of(port) : Optional.empty();
    }

    private CardObject egk() {
        return CardObject.builder().cardHandle("egk").ctid(ctid).slotNo(1).type(CardType.EGK).build();
    }

    private CardObject smcb() {
        return CardObject.builder().cardHandle("smcb").ctid(ctid).slotNo(2).type(CardType.SMC_B).build();
    }

    /** The happy-path script up to (and including) MSE Set internalAuthenticate. */
    private List<byte[]> scriptThroughChallenge() {
        byte[] ca = cvc(bytes(8, 0x11), bytes(12, 0x22));
        byte[] leaf = cvc(bytes(8, 0x22), bytes(12, 0x33));
        byte[] gdo = concat(new byte[]{0x5A, 0x0A}, bytes(10, 0x44)); // ICCSN; iccsn8 = last 8 octets
        List<byte[]> s = new ArrayList<>();
        s.add(ScriptedCardReaderPort.ok());                 // SELECT MF (hpc)
        s.add(ScriptedCardReaderPort.ok());                 // SELECT EF.C.CA.CS.E256 (2F07)
        s.add(ScriptedCardReaderPort.resp(ca, 0x9000));     // READ CA CVC
        s.add(ScriptedCardReaderPort.ok());                 // SELECT EF.C.*.AUTR_CVC.E256 (2F06)
        s.add(ScriptedCardReaderPort.resp(leaf, 0x9000));   // READ leaf CVC
        s.add(ScriptedCardReaderPort.ok());                 // SELECT MF (egk)
        s.add(ScriptedCardReaderPort.ok());                 // SELECT EF.GDO (2F02)
        s.add(ScriptedCardReaderPort.resp(gdo, 0x9000));    // READ GDO
        s.add(ScriptedCardReaderPort.ok());                 // MSE B6 (CA)
        s.add(ScriptedCardReaderPort.ok());                 // PSO Verify (CA)
        s.add(ScriptedCardReaderPort.ok());                 // MSE B6 (leaf)
        s.add(ScriptedCardReaderPort.ok());                 // PSO Verify (leaf)
        s.add(ScriptedCardReaderPort.ok());                 // MSE A4 externalAuthenticate
        s.add(ScriptedCardReaderPort.resp(bytes(16, 0x55), 0x9000)); // GET CHALLENGE (16)
        s.add(ScriptedCardReaderPort.ok());                 // MSE A4 internalAuthenticate
        return s;
    }

    @Test
    void roleAuth_runs_handshake_and_returns_plaintext_channel() throws Exception {
        List<byte[]> s = scriptThroughChallenge();
        s.add(ScriptedCardReaderPort.resp(bytes(64, 0x66), 0x9000)); // INTERNAL AUTHENTICATE (R||S)
        s.add(ScriptedCardReaderPort.ok());                          // EXTERNAL AUTHENTICATE
        var port = new ScriptedCardReaderPort(ctid, s);

        Optional<ApduSecureChannel> channel = new ElcCardToCardAuthenticator()
                .authenticate(resolver(port), egk(), smcb());

        assertTrue(channel.isPresent(), "C2C role auth succeeded → GVD authorised");
        assertSame(ApduSecureChannel.NONE, channel.get(), "GVD is read in plaintext (flagTI.30) — no SM");
    }

    @Test
    void aborts_3041_when_smcb_pin_not_verified() {
        List<byte[]> s = scriptThroughChallenge();
        s.add(ScriptedCardReaderPort.resp(new byte[0], 0x6982)); // INTERNAL AUTHENTICATE refused
        var port = new ScriptedCardReaderPort(ctid, s);

        CardToCardAuthException ex = assertThrows(CardToCardAuthException.class,
                () -> new ElcCardToCardAuthenticator().authenticate(resolver(port), egk(), smcb()));
        assertEquals(CardToCardAuthException.Reason.SMB_SECURITY_STATE_INSUFFICIENT, ex.reason());
    }
}
