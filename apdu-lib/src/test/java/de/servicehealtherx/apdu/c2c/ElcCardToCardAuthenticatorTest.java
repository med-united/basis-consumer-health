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
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.vsdm.FakeCardReaderPort;
import de.servicehealtherx.apdu.vsdm.VsdmErrorCode;
import de.servicehealtherx.apdu.vsdm.VsdmReadException;

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

    private CardReaderPortResolver resolver(FakeCardReaderPort port) {
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
        var port = new FakeCardReaderPort(ctid, List.of(
                FakeCardReaderPort.ok(),                    // PIN status
                FakeCardReaderPort.ok(),                    // SELECT hpc leaf CVC
                FakeCardReaderPort.resp(cvc, 0x9000),       // READ hpc leaf CVC
                FakeCardReaderPort.ok(),                    // SELECT hpc CA CVC
                FakeCardReaderPort.resp(cvc, 0x9000),       // READ hpc CA CVC
                FakeCardReaderPort.ok(),                    // PSO VERIFY CERTIFICATE
                FakeCardReaderPort.ok(),                    // MSE SET
                FakeCardReaderPort.resp(new byte[]{0x7C, 0x02, (byte) 0x85, 0x00}, 0x9000), // GA step 1
                FakeCardReaderPort.resp(new byte[]{0x7C, 0x02, (byte) 0x85, 0x00}, 0x9000)  // GA step 2
        ));
        var auth = new ElcCardToCardAuthenticator(fixedKeys);

        Optional<ApduSecureChannel> channel = auth.authenticate(resolver(port), egk(), smcb());
        assertTrue(channel.isPresent(), "C2C established → SM channel returned");
    }

    @Test
    void test_VSDM_A_2572_aborts_3041_when_smcb_pin_not_enabled() {
        var port = new FakeCardReaderPort(ctid, List.of(
                FakeCardReaderPort.resp(null, 0x6982)));    // PIN status: security not satisfied
        var auth = new ElcCardToCardAuthenticator(fixedKeys);
        VsdmReadException ex = assertThrows(VsdmReadException.class,
                () -> auth.authenticate(resolver(port), egk(), smcb()));
        assertEquals(VsdmErrorCode.SMB_NOT_ENABLED, ex.errorCode());
    }
}
