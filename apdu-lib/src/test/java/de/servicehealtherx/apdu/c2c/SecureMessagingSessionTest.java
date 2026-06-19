package de.servicehealtherx.apdu.c2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.junit.jupiter.api.Test;

/**
 * Structural tests for the Secure-Messaging framing. The full handshake is hardware-bound and not
 * validated here (research.md C2C risk note); these assert the wrap/unwrap envelope shape only.
 */
class SecureMessagingSessionTest {

    private SecureMessagingSession session() {
        return new SecureMessagingSession(new byte[16], new byte[16], new byte[16]);
    }

    @Test
    void wrap_sets_sm_class_byte_and_appends_mac_do() {
        CommandAPDU wrapped = session().wrap(new CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256));
        assertTrue((wrapped.getCLA() & 0x0C) == 0x0C, "CLA marks Secure Messaging");
        byte[] body = wrapped.getData();
        assertTrue(containsTag(body, 0x8E), "carries the DO'8E' MAC");
        assertTrue(containsTag(body, 0x97), "carries the DO'97' Le for a READ");
    }

    @Test
    void wrap_encrypts_command_data_into_do87() {
        // UPDATE BINARY with a data field exercises the AES-CBC encrypt + SSC-IV path.
        CommandAPDU wrapped = session().wrap(new CommandAPDU(0x00, 0xD6, 0x00, 0x00, new byte[]{1, 2, 3, 4}));
        assertTrue(containsTag(wrapped.getData(), 0x87), "encrypted command data carried in DO'87'");
        assertTrue(containsTag(wrapped.getData(), 0x8E), "carries the DO'8E' MAC");
    }

    @Test
    void unwrap_recovers_status_word_from_do99() {
        // SM response body: DO'99' (SW=9000) ‖ DO'8E' (8-byte MAC placeholder)
        byte[] body = {(byte) 0x99, 0x02, (byte) 0x90, 0x00,
                (byte) 0x8E, 0x08, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0x90, 0x00};
        ResponseAPDU plain = session().unwrap(new ResponseAPDU(body));
        assertEquals(0x9000, plain.getSW());
        assertEquals(0, plain.getData().length);
    }

    private static boolean containsTag(byte[] data, int tag) {
        for (byte b : data) {
            if ((b & 0xFF) == tag) {
                return true;
            }
        }
        return false;
    }
}
