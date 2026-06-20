package de.servicehealtherx.apdu.c2c;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GematikISO7816;

/**
 * One-sided ELC card-to-card role authentication of the HBA/SMC-B (Quellkarte) against the eGK
 * (Zielkarte) — TUC_KON_005 {@code AuthMode=einseitig}, {@code sKeyRef=*.AUTR_CVC.E256} (gemSpec_Kon
 * TAB_KON_674 "Freischaltung eGK"). This raises the eGK's role-authenticated security state
 * ({@code flagTI.30}) so that the protected VSDM file {@code EF.GVD} becomes readable.
 *
 * <p><strong>No Secure Messaging and no session keys are involved</strong>: for the VSDM read use
 * case gemSpec_Kon mandates the einseitig variant (gemSpec_COS §15.1.3/§15.2), not the
 * {@code gegenseitig+TC} ELC-sessionkey path. After this handshake {@code EF.GVD} is read in
 * plaintext via the {@code flagTI.30} branch of its access rule (gemSpec_eGK_ObjSys §5.4.2) — the
 * {@code {AUT_VSD}}/{@code SK.VSD} Secure-Messaging branch is the separate CMS data-update path.
 * Accordingly {@link #authenticate} returns {@link ApduSecureChannel#NONE} on success.
 *
 * <p>Host responsibilities (the cryptography runs on-card): read the partner's CVC chain, drive the
 * eGK's {@code PSO Verify Certificate} import and {@code EXTERNAL AUTHENTICATE}, relay the eGK
 * challenge to the partner's {@code INTERNAL AUTHENTICATE}. The token signed by the partner is
 * {@code RND.ICC(16) || iccsn8(eGK)(8)} (gemSpec_COS (N084.400)a.4 / (N086.900)a). The partner's
 * {@code INTERNAL AUTHENTICATE} requires its card-holder PIN (PIN.SMC / PIN.CH) to be verified
 * beforehand — verifying it is the caller's responsibility (session management); a missing PIN
 * surfaces here as {@link CardToCardAuthException.Reason#SMB_SECURITY_STATE_INSUFFICIENT}/
 * {@link CardToCardAuthException.Reason#HBA_SECURITY_STATE_INSUFFICIENT}.
 */
public final class ElcCardToCardAuthenticator implements CardToCardAuthenticator {

    // MF-level CVC files on the HBA/SMC-B (gemSpec_{SMC-B,HBA}_ObjSys §5.3.6/§5.3.7):
    // EF.C.CA.CS.E256 = issuing sub-CA CVC (SFID 7), EF.C.*.AUTR_CVC.E256 = leaf role CVC (SFID 6).
    private static final short FID_C_CA_CS_E256 = (short) 0x2F07;
    private static final short FID_C_AUTR_CVC_E256 = (short) 0x2F06;
    private static final short FID_EF_GDO = (short) 0x2F02;

    /** keyIdentifier of PrK.{SMC,HPC}.AUTR_CVC.E256 (both '06'); MSE keyRef = location '00' | id. */
    private static final int AUTR_PRIVATE_KEY_ID = 0x06;

    /** elcRoleAuthentication (INTERNAL AUTHENTICATE) algId; elcRoleCheck (EXTERNAL) pairs to the same value (gemSpec_COS CosT_7a2). */
    private static final int ALG_ELC_ROLE = 0x00;

    private static final int GET_CHALLENGE_LEN = 0x10; // 16 octets for ELC (gemSpec_COS N098.620)
    private static final int ICCSN8_LEN = 8;
    private static final int READ_CHUNK = 0xFF;        // some readers reject Le=0x00 (256) with SW 6700

    private final CvcChainParser cvcParser = new CvcChainParser();

    /** Cross-CV-certificates (ordered root→down) to extend the eGK's CVC trust to the partner's root. */
    private final List<byte[]> crossCvcs;

    public ElcCardToCardAuthenticator() {
        this(List.of());
    }

    /**
     * @param crossCvcs cross-CVCs imported into the eGK before the partner chain when the partner's
     *                  CVC root is not personalised on the eGK (TUC_KON_005 variant (9)); each is a
     *                  full {@code 7F21} CVC verifiable by the previously trusted key, ordered so the
     *                  first is verifiable by a root the eGK already holds.
     */
    public ElcCardToCardAuthenticator(List<byte[]> crossCvcs) {
        this.crossCvcs = List.copyOf(crossCvcs);
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

        // 1. Read the partner card's CVC chain (CA cert + leaf role CVC) from its MF.
        selectMf(hpcPort, hpc.slotNo());
        byte[] caCvc = readBinary(hpcPort, hpc.slotNo(), FID_C_CA_CS_E256);
        byte[] leafCvc = readBinary(hpcPort, hpc.slotNo(), FID_C_AUTR_CVC_E256);
        CvCertificate leaf = cvcParser.parse(leafCvc); // validates structure; yields CAR/CHR

        // 2. eGK iccsn8 = last 8 octets of the EF.GDO body (gemSpec_eGK_ObjSys Card-G2-A_2343).
        selectMf(egkPort, egk.slotNo());
        byte[] gdo = readBinary(egkPort, egk.slotNo(), FID_EF_GDO);
        byte[] iccsn8 = lastOctets(gdo, ICCSN8_LEN);

        // 3. Import the partner's CVC chain into the eGK so it learns the partner's public key + role.
        for (byte[] crossCvc : crossCvcs) {
            importCertificate(egkPort, egk.slotNo(), crossCvc);
        }
        importCertificate(egkPort, egk.slotNo(), caCvc);
        importCertificate(egkPort, egk.slotNo(), leafCvc);

        // 4. eGK: select the imported partner key for EXTERNAL AUTHENTICATE, then draw a challenge.
        mseExternalAuth(egkPort, egk.slotNo(), leaf.chr());
        byte[] rndIcc = getChallenge(egkPort, egk.slotNo());
        byte[] token = concat(rndIcc, iccsn8);

        // 5. Partner: select PrK.*.AUTR and sign the token (needs PIN.SMC/PIN.CH already verified).
        mseInternalAuth(hpcPort, hpc.slotNo());
        byte[] signature = internalAuthenticate(hpcPort, hpc.slotNo(), token, hpc);

        // 6. eGK: verify the partner's response — raises the role-authenticated state (flagTI.30).
        externalAuthenticate(egkPort, egk.slotNo(), signature);

        // EF.GVD is now readable in plaintext via flagTI.30 — no Secure Messaging needed.
        return Optional.of(ApduSecureChannel.NONE);
    }

    // ── eGK certificate import ────────────────────────────────────────────────────────────────────

    /** {@code MSE Set DST (B6)} selecting the issuer key by CAR, then {@code PSO Verify Certificate}. */
    private void importCertificate(CardReaderPort port, int slot, byte[] cvc) throws CardTransportException {
        CvCertificate cert = cvcParser.parse(cvc);
        ResponseAPDU mse = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV, 0x81, 0xB6,
                tlv(0x83, cert.car())));
        if (mse.getSW() != GematikISO7816.SW_SUCCESS) {
            // 6A88 → the issuer/root key is not on the eGK (needs a cross-CVC, TUC_KON_005 variant (9)).
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "MSE Set verifyCertificate (CAR " + hex(cert.car()) + ") failed: " + sw(mse.getSW()));
        }
        ResponseAPDU pso = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_PERFORM_SECURITY_OPERATION, 0x00, 0xBE,
                inner7F21(cvc)));
        // 63 Cx is UpdateRetryWarning — treated as success (gemSpec_COS CosT_b8a).
        if (pso.getSW() != GematikISO7816.SW_SUCCESS && (pso.getSW() & 0xFFF0) != 0x63C0) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "PSO Verify Certificate (CHR " + hex(cert.chr()) + ") failed: " + sw(pso.getSW()));
        }
    }

    // ── role-authentication APDUs ─────────────────────────────────────────────────────────────────

    /** {@code MSE Set AT (A4)} for EXTERNAL AUTHENTICATE: public key by 12-octet CHR + elcRoleCheck. */
    private void mseExternalAuth(CardReaderPort port, int slot, byte[] leafChr) throws CardTransportException {
        byte[] data = concat(tlv(0x83, leafChr), tlv(0x80, new byte[]{(byte) ALG_ELC_ROLE}));
        ResponseAPDU resp = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV, 0x81, 0xA4, data));
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "MSE Set externalAuthenticate failed: " + sw(resp.getSW()));
        }
    }

    private byte[] getChallenge(CardReaderPort port, int slot) throws CardTransportException {
        ResponseAPDU resp = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_GET_CHALLENGE, 0x00, 0x00, GET_CHALLENGE_LEN));
        if (resp.getSW() != GematikISO7816.SW_SUCCESS || resp.getData().length != GET_CHALLENGE_LEN) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "GET CHALLENGE failed: " + sw(resp.getSW()));
        }
        return resp.getData();
    }

    /** {@code MSE Set AT (A4)} for INTERNAL AUTHENTICATE: private key '06' + elcRoleAuthentication. */
    private void mseInternalAuth(CardReaderPort port, int slot) throws CardTransportException {
        byte[] data = concat(tlv(0x84, new byte[]{(byte) AUTR_PRIVATE_KEY_ID}), tlv(0x80, new byte[]{(byte) ALG_ELC_ROLE}));
        ResponseAPDU resp = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_MANAGE_SECURITY_ENV, 0x41, 0xA4, data));
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "MSE Set internalAuthenticate failed: " + sw(resp.getSW()));
        }
    }

    private byte[] internalAuthenticate(CardReaderPort port, int slot, byte[] token, CardObject hpc)
            throws CardTransportException {
        ResponseAPDU resp = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_INTERNAL_AUTHENTICATE, 0x00, 0x00, token, READ_CHUNK));
        int swv = resp.getSW();
        if (swv == GematikISO7816.SW_SECURITY_NOT_SATISFIED || swv == GematikISO7816.SW_AUTH_METHOD_BLOCKED) {
            // PIN.SMC / PIN.CH not verified (or blocked) — the partner could not sign the challenge.
            throw new CardToCardAuthException(pinReason(hpc),
                    "INTERNAL AUTHENTICATE refused (PIN not verified): " + sw(swv));
        }
        if (swv != GematikISO7816.SW_SUCCESS || resp.getData().length == 0) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "INTERNAL AUTHENTICATE failed: " + sw(swv));
        }
        return resp.getData();
    }

    private void externalAuthenticate(CardReaderPort port, int slot, byte[] signature) throws CardTransportException {
        ResponseAPDU resp = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_EXTERNAL_AUTHENTICATE, 0x00, 0x00, signature));
        if (resp.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "EXTERNAL AUTHENTICATE failed: " + sw(resp.getSW()));
        }
    }

    private static CardToCardAuthException.Reason pinReason(CardObject hpc) {
        return switch (hpc.type()) {
            case HBA, HBAX -> CardToCardAuthException.Reason.HBA_SECURITY_STATE_INSUFFICIENT;
            default -> CardToCardAuthException.Reason.SMB_SECURITY_STATE_INSUFFICIENT;
        };
    }

    // ── transport helpers ─────────────────────────────────────────────────────────────────────────

    private static void selectMf(CardReaderPort port, int slot) throws CardTransportException {
        port.transmit(slot, new CommandAPDU(GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_FIRST_OCCURRENCE, 0x0C, new byte[]{0x3F, 0x00}));
    }

    /** SELECT EF by file id (no FCI) then READ BINARY in {@value #READ_CHUNK}-octet chunks. */
    private static byte[] readBinary(CardReaderPort port, int slot, short fid) throws CardTransportException {
        byte[] fidBytes = {(byte) (fid >> 8), (byte) (fid & 0xFF)};
        ResponseAPDU sel = port.transmit(slot, new CommandAPDU(
                GematikISO7816.CLA_ISO, GematikISO7816.INS_SELECT,
                GematikISO7816.SELECT_BY_FILE_ID, 0x0C, fidBytes));
        if (sel.getSW() != GematikISO7816.SW_SUCCESS) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "SELECT " + String.format("%04X", fid & 0xFFFF) + " failed: " + sw(sel.getSW()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < 0x8000) {
            ResponseAPDU resp = port.transmit(slot, new CommandAPDU(
                    GematikISO7816.CLA_ISO, GematikISO7816.INS_READ_BINARY,
                    (offset >> 8) & 0x7F, offset & 0xFF, READ_CHUNK));
            int swv = resp.getSW();
            if (swv != GematikISO7816.SW_SUCCESS && (swv & 0xFF00) != 0x6200) {
                if (out.size() > 0) {
                    break;
                }
                throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                        "READ BINARY " + String.format("%04X", fid & 0xFFFF) + " failed: " + sw(swv));
            }
            byte[] chunk = resp.getData();
            out.write(chunk, 0, chunk.length);
            offset += chunk.length;
            if (chunk.length < READ_CHUNK || (swv & 0xFF00) == 0x6200) {
                break;
            }
        }
        return out.toByteArray();
    }

    /** The value of the outer {@code 7F21} (i.e. {@code 7F4E…||5F37…}) — the PSO Verify cert content. */
    private static byte[] inner7F21(byte[] cvc) {
        int p = 0;
        int tag = cvc[p++] & 0xFF;
        if ((tag & 0x1F) == 0x1F) {
            tag = (tag << 8) | (cvc[p++] & 0xFF);
        }
        int len = cvc[p++] & 0xFF;
        if (len > 0x80) {
            int n = len & 0x7F;
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (cvc[p++] & 0xFF);
            }
        }
        byte[] inner = new byte[len];
        System.arraycopy(cvc, p, inner, 0, len);
        return inner;
    }

    private static byte[] lastOctets(byte[] data, int n) {
        if (data.length < n) {
            throw new CardToCardAuthException(CardToCardAuthException.Reason.CVC_READ_FAILED,
                    "EF.GDO too short for iccsn8");
        }
        byte[] out = new byte[n];
        System.arraycopy(data, data.length - n, out, 0, n);
        return out;
    }

    private static byte[] tlv(int tag, byte[] value) {
        byte[] out = new byte[2 + value.length];
        out[0] = (byte) tag;
        out[1] = (byte) value.length;
        System.arraycopy(value, 0, out, 2, value.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String sw(int s) {
        return String.format("%04X", s);
    }

    private static String hex(byte[] b) {
        if (b == null) {
            return "null";
        }
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02X", x));
        }
        return s.toString();
    }
}
