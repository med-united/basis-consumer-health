package de.servicehealtherx.apdu.c2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
 * Structural test of the host-side C2C APDU sequencing. The on-card crypto is not exercised; the
 * session keys come from a deterministic test {@link SessionKeyDerivation}.
 */
class ElcCardToCardAuthenticatorTest {

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

    private static byte[] minimalCvc() {
        byte[] body = tlv(0x42, "CAR".getBytes());
        return tlv(0x7F21, tlv(0x7F4E, body));
    }

    private final SessionKeyDerivation fixedKeys = r -> new byte[][]{new byte[16], new byte[16], new byte[16]};

    private CardReaderPortResolver resolver(ScriptedCardReaderPort port) {
        return id -> id.equals(ctid) ? Optional.of(port) : Optional.empty();
    }

    private CardObject egk() {
        return CardObject.builder().cardHandle("egk").ctid(ctid).slotNo(1).type(CardType.EGK).build();
    }

    private CardObject smcb() {
        return CardObject.builder().cardHandle("smcb").ctid(ctid).slotNo(2).type(CardType.SMC_B).build();
    }

    @Test
    void test_VSDM_A_2572_runs_handshake_and_returns_sm_channel() throws Exception {
        byte[] cvc = minimalCvc();
        var port = new ScriptedCardReaderPort(ctid, List.of(
                ScriptedCardReaderPort.ok(),                    // PIN status
                ScriptedCardReaderPort.ok(),                    // SELECT hpc leaf CVC
                ScriptedCardReaderPort.resp(cvc, 0x9000),       // READ hpc leaf CVC
                ScriptedCardReaderPort.ok(),                    // SELECT hpc CA CVC
                ScriptedCardReaderPort.resp(cvc, 0x9000),       // READ hpc CA CVC
                ScriptedCardReaderPort.ok(),                    // PSO VERIFY CERTIFICATE
                ScriptedCardReaderPort.ok(),                    // MSE SET
                ScriptedCardReaderPort.resp(new byte[]{0x7C, 0x02, (byte) 0x85, 0x00}, 0x9000), // GA step 1
                ScriptedCardReaderPort.resp(new byte[]{0x7C, 0x02, (byte) 0x85, 0x00}, 0x9000)  // GA step 2
        ));
        var auth = new ElcCardToCardAuthenticator(fixedKeys);

        Optional<ApduSecureChannel> channel = auth.authenticate(resolver(port), egk(), smcb());
        assertTrue(channel.isPresent(), "C2C established → SM channel returned");
    }

    @Test
    void test_VSDM_A_2572_aborts_3041_when_smcb_pin_not_enabled() {
        var port = new ScriptedCardReaderPort(ctid, List.of(
                ScriptedCardReaderPort.resp(null, 0x6982)));    // PIN status: security not satisfied
        var auth = new ElcCardToCardAuthenticator(fixedKeys);
        CardToCardAuthException ex = assertThrows(CardToCardAuthException.class,
                () -> auth.authenticate(resolver(port), egk(), smcb()));
        assertEquals(CardToCardAuthException.Reason.SMB_SECURITY_STATE_INSUFFICIENT, ex.reason());
    }
}
