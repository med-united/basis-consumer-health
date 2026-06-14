package de.servicehealtherx.crypto.pcsc;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardTransportException;

/**
 * Hand-written {@link PcscTerminal} test double — lets the PC/SC port/registry be tested without a
 * live PC/SC subsystem and without adding Mockito.
 */
final class FakePcscTerminal implements PcscTerminal {

    private final String name;
    private boolean present;
    private boolean failTransmit;
    // Default canned response: EF.GDO with a short ICCSN + SW 9000, so handle creation succeeds.
    private ResponseAPDU canned =
            new ResponseAPDU(new byte[] {0x5A, 0x02, 0x12, 0x34, (byte) 0x90, 0x00});

    FakePcscTerminal(String name, boolean present) {
        this.name = name;
        this.present = present;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isCardPresent() {
        return present;
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU command) throws CardTransportException {
        if (failTransmit) {
            throw new CardTransportException("simulated PC/SC failure");
        }
        return canned;
    }

    void setPresent(boolean present) {
        this.present = present;
    }

    void setFailTransmit(boolean failTransmit) {
        this.failTransmit = failTransmit;
    }
}
