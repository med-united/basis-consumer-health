package de.servicehealtherx.apdu.c2c;

import java.util.List;
import java.util.Optional;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.ApduExecutor;
import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.ExpectedStatusSet;
import de.servicehealtherx.apdu.model.GeneratedApduStep;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.apdu.model.TucGenerationResult;

/**
 * ELC card-to-card authenticator (TUC_KON_005) that drives the host-side APDU sequence — PIN-status
 * check, CVC read + on-card {@code PSO VERIFY CERTIFICATE}, {@code MSE SET}, two-step
 * {@code GENERAL AUTHENTICATE} — and returns the Secure-Messaging channel for the EF.GVD read.
 *
 * <p>The cryptographic primitives (ECDSA verification, ECKA-DH, session-key derivation) run
 * on-card; the host only sequences APDUs and parses CVCs ({@link CvcChainParser}). The session keys
 * are produced by the injected {@link SessionKeyDerivation} — which is {@link SessionKeyDerivation#ON_CARD}
 * (throws) in production, because that step is hardware-bound (see research.md C2C risk note). This
 * class is therefore <strong>not wired in production</strong> by default; it is exercised by
 * structural unit tests with fake cards and a deterministic key derivation.
 */
public final class ElcCardToCardAuthenticator implements CardToCardAuthenticator {

    // CVC files (gemSpec_eGK_ObjSys / SMC-B ObjSys): leaf AUT_CVC SFID 6, CA link SFID 7.
    private static final short FID_C_AUT_CVC_E256 = (short) 0x2F06;
    private static final short FID_C_CA_CS_E256 = (short) 0x2F07;

    private final SessionKeyDerivation keyDerivation;
    private final CvcChainParser cvcParser = new CvcChainParser();

    public ElcCardToCardAuthenticator(SessionKeyDerivation keyDerivation) {
        this.keyDerivation = keyDerivation;
    }

    @Override
    public Optional<ApduSecureChannel> authenticate(CardReaderPortResolver resolver, CardObject egk, CardObject hpc)
            throws CardTransportException {
        CardReaderPort egkPort = resolver.portFor(egk.ctid())
                .orElseThrow(() -> new CardToCardAuthException(
                        CardToCardAuthException.Reason.EGK_READER_UNAVAILABLE, "eGK reader unavailable"));
        CardReaderPort hpcPort = resolver.portFor(hpc.ctid())
                .orElseThrow(() -> new CardToCardAuthException(
                        CardToCardAuthException.Reason.HPC_READER_UNAVAILABLE, "HBA/SMC-B reader unavailable"));

        checkPinEnabled(hpcPort, hpc);

        // Read the partner card's CVC leaf and parse it (host orders the chain; the card verifies it).
        byte[] hpcCvc = readBinaryFile(hpcPort, hpc.slotNo(), FID_C_AUT_CVC_E256);
        cvcParser.parse(hpcCvc); // validates structure; throws if not a CVC
        readBinaryFile(hpcPort, hpc.slotNo(), FID_C_CA_CS_E256);

        // Run PSO VERIFY CERTIFICATE + MSE SET + two-step GENERAL AUTHENTICATE on the eGK.
        List<ResponseAPDU> handshake = new ApduExecutor(egkPort).execute(handshakeSteps(hpcCvc), egk.slotNo());

        byte[][] keys = keyDerivation.derive(handshake);
        return Optional.of(new SecureMessagingSession(keys[0], keys[1], keys[2]));
    }

    private void checkPinEnabled(CardReaderPort port, CardObject hpc) throws CardTransportException {
        // PIN security-state query (TUC_KON_022). A non-success status means the card's PIN
        // (PIN.SMC / PIN.CH) is not enabled, so C2C cannot proceed.
        CommandAPDU pinStatus = new CommandAPDU(0x80, GematikISO7816.INS_GET_DATA, 0x00, 0xDF);
        ResponseAPDU resp = port.transmit(hpc.slotNo(), pinStatus);
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            CardToCardAuthException.Reason reason = (hpc.type() == CardType.SMC_B)
                    ? CardToCardAuthException.Reason.SMB_SECURITY_STATE_INSUFFICIENT
                    : CardToCardAuthException.Reason.HBA_SECURITY_STATE_INSUFFICIENT;
            throw new CardToCardAuthException(reason, "card security state insufficient: " + hpc.cardHandle());
        }
    }

    private static byte[] readBinaryFile(CardReaderPort port, int slot, short fid) throws CardTransportException {
        byte[] fidBytes = {(byte) (fid >> 8), (byte) (fid & 0xFF)};
        ResponseAPDU sel = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fidBytes));
        if (sel.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "SELECT CVC " + String.format("%04X", fid & 0xFFFF) + " failed");
        }
        ResponseAPDU read = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY, 0x00, 0x00, 256));
        return read.getData();
    }

    private static TucGenerationResult handshakeSteps(byte[] hpcCvc) {
        ExpectedStatusSet ok = ExpectedStatusSet.successOnly();
        var psoVerify = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_PERFORM_SECURITY_OPERATION,
                0x00, 0xBE, hpcCvc);
        var mseSet = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV, 0x81, 0xA4);
        var gaStep1 = new CommandAPDU(GematikISO7816.CLA_CHAIN, GematikISO7816.INS_GENERAL_AUTHENTICATE, 0x00, 0x00,
                new byte[]{0x7C, 0x00}, 256);
        var gaStep2 = new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_GENERAL_AUTHENTICATE, 0x00, 0x00,
                new byte[]{0x7C, 0x00}, 256);
        return TucGenerationResult.of(List.of(
                new GeneratedApduStep(psoVerify, ok, "PSO VERIFY CERTIFICATE"),
                new GeneratedApduStep(mseSet, ok, "MSE SET (elcSessionkey)"),
                new GeneratedApduStep(gaStep1, ok, "GENERAL AUTHENTICATE step 1"),
                new GeneratedApduStep(gaStep2, ok, "GENERAL AUTHENTICATE step 2")));
    }
}
