package de.servicehealtherx.apdu.card;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

/**
 * A Secure-Messaging channel that wraps an outgoing command and unwraps the incoming response.
 *
 * <p>The AlwaysRead VSDM files (EF.PD/EF.VD/EF.StatusVD) use {@link #NONE} (plain APDUs). EF.GVD,
 * after a successful card-to-card authentication, is read through the SM session established by the
 * C2C handshake ({@link de.servicehealtherx.apdu.c2c.SecureMessagingSession}), which protects the
 * READ BINARY with an AES-CMAC and command/response encryption (AUT_VSD).
 */
public interface ApduSecureChannel {

    /** Passthrough channel for AlwaysRead files — no Secure Messaging. */
    ApduSecureChannel NONE = new ApduSecureChannel() {
        @Override
        public CommandAPDU wrap(CommandAPDU command) {
            return command;
        }

        @Override
        public ResponseAPDU unwrap(ResponseAPDU response) {
            return response;
        }
    };

    CommandAPDU wrap(CommandAPDU command);

    ResponseAPDU unwrap(ResponseAPDU response);
}
