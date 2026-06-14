package de.servicehealtherx.crypto.pcsc;

import javax.smartcardio.Card;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardTransportException;

/**
 * Real {@link PcscTerminal} backed by a JDK {@code javax.smartcardio.CardTerminal} (research D11).
 * Each {@link #transmit} connects with the negotiated protocol, sends the APDU on the basic
 * channel, and disconnects without resetting the card.
 */
public final class SmartcardioPcscTerminal implements PcscTerminal {

    private final CardTerminal terminal;

    public SmartcardioPcscTerminal(CardTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return terminal.getName();
    }

    @Override
    public boolean isCardPresent() {
        try {
            return terminal.isCardPresent();
        } catch (CardException e) {
            return false;
        }
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU command) throws CardTransportException {
        Card card = null;
        try {
            card = terminal.connect("*");
            return card.getBasicChannel().transmit(command);
        } catch (CardException e) {
            throw new CardTransportException("PC/SC transmit failed on reader " + name(), e);
        } finally {
            if (card != null) {
                try {
                    card.disconnect(false);
                } catch (CardException ignored) {
                    // best effort
                }
            }
        }
    }
}
