package de.servicehealtherx.crypto.sicct;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.card.transport.ReaderCapabilities;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the SICCT provider's card-backed crypto operations (sign / decipher) through a scripted
 * {@link CardReaderPort}, asserting the ISO 7816 dialogue (SELECT → VERIFY → MSE:SET → PSO) and that
 * the card's PSO response data is returned unchanged — mirroring the PC/SC provider's behaviour.
 */
class SicctCryptoProviderCryptoTest {

    private static final int SLOT = 1;

    @Test
    void external_authenticate_runs_select_verify_mse_dst_pso_cds() {
        byte[] signature = bytes(0x30, 0x44, 0xAA, 0xBB);
        FakePort port = pso(0x9E, signature); // PSO:COMPUTE DIGITAL SIGNATURE

        SicctCryptoProvider provider = new SicctCryptoProvider();
        String handle = addCard(provider, port);

        byte[] result = provider.externalAuthenticate(handle, bytes(0x01, 0x02));

        assertArrayEquals(signature, result);
        assertTrue(port.sawMse(0xB6), "ExternalAuthenticate must MSE:SET the Digital Signature Template");
        assertTrue(port.sawPso(0x9E, 0x9A), "ExternalAuthenticate must PSO:COMPUTE DIGITAL SIGNATURE");
    }

    @Test
    void decrypt_runs_mse_ct_then_pso_decipher_and_returns_transport_key() {
        byte[] transportKey = new byte[32];
        for (int i = 0; i < transportKey.length; i++) {
            transportKey[i] = (byte) i;
        }
        FakePort port = pso(0x80, transportKey); // PSO:DECIPHER

        SicctCryptoProvider provider = new SicctCryptoProvider();
        String handle = addCard(provider, port);
        KeyAlias alias = new KeyAlias("sicct/" + handle);

        CryptoOperationResult result =
                provider.decrypt(new CryptoOperationRequest(alias, "ELC", bytes(0xDE, 0xAD), "tester"));

        assertArrayEquals(transportKey, result.result);
        assertTrue(port.sawMse(0xB8), "Decrypt must MSE:SET the Confidentiality Template");
        assertTrue(port.sawPso(0x80, 0x86), "Decrypt must PSO:DECIPHER");
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static String addCard(SicctCryptoProvider provider, FakePort port) {
        String handle = provider.cmCardList().generateCardHandle();
        provider.cmCardList().add(CardObject.builder()
                .cardHandle(handle).ctid(port.ctid()).slotNo(SLOT).type(CardType.SMC_B).build());
        provider.bindPortResolver(ctid -> ctid.equals(port.ctid()) ? Optional.of(port) : Optional.empty());
        return handle;
    }

    /** Scripts a port that answers everything with 9000 and the PSO of the given P1 with {@code data}. */
    private static FakePort pso(int psoP1, byte[] data) {
        return new FakePort("sicct-crypto", (slot, cmd) -> {
            if (cmd.getINS() == GematikISO7816.INS_PERFORM_SECURITY_OPERATION && cmd.getP1() == psoP1) {
                byte[] body = new byte[data.length + 2];
                System.arraycopy(data, 0, body, 0, data.length);
                body[data.length] = (byte) 0x90;
                body[data.length + 1] = 0x00;
                return new ResponseAPDU(body);
            }
            return new ResponseAPDU(new byte[] {(byte) 0x90, 0x00});
        });
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    /** {@link CardReaderPort} double that records the APDU dialogue for assertions. */
    private static final class FakePort implements CardReaderPort {
        private final UUID ctid;
        private final BiFunction<Integer, CommandAPDU, ResponseAPDU> responder;
        private final List<CommandAPDU> commands = new ArrayList<>();

        FakePort(String name, BiFunction<Integer, CommandAPDU, ResponseAPDU> responder) {
            this.ctid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            this.responder = responder;
        }

        boolean sawMse(int p2) {
            return commands.stream().anyMatch(c ->
                    c.getINS() == GematikISO7816.INS_MANAGE_SECURITY_ENV
                            && c.getP1() == GematikISO7816.MSE_SET_COMPUTE && c.getP2() == p2);
        }

        boolean sawPso(int p1, int p2) {
            return commands.stream().anyMatch(c ->
                    c.getINS() == GematikISO7816.INS_PERFORM_SECURITY_OPERATION
                            && c.getP1() == p1 && c.getP2() == p2);
        }

        @Override public String readerName() { return "sicct-crypto"; }
        @Override public UUID ctid() { return ctid; }
        @Override public ReaderCapabilities capabilities() { return ReaderCapabilities.sicctDefault(); }
        @Override public boolean isCardPresent(int slotNo) { return true; }
        @Override public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
            commands.add(command);
            return responder.apply(slotNo, command);
        }
        @Override public void addPresenceListener(PresenceListener listener) { }
        @Override public void removePresenceListener(PresenceListener listener) { }
    }
}
