package de.servicehealtherx.sicct;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Implements the EHEALTH TERMINAL AUTHENTICATE pairing protocol.
 * Produces and parses SICCT APDUs for phases CREATE, VALIDATE, ADD (Phase 1/2).
 * Reference: gemSpec_KT_V3.17.0 §3.7.2; FR-130–FR-132.
 */
@ApplicationScoped
public class EhealthAuthenticator {

    private static final Logger LOG = Logger.getLogger(EhealthAuthenticator.class);

    private static final byte INS_EHEALTH_AUTH = (byte) 0xAA;
    private static final byte P2_CREATE = 0x01;
    private static final byte P2_VALIDATE = 0x02;
    private static final byte P2_ADD_PHASE1 = 0x03;
    private static final byte P2_ADD_PHASE2 = 0x04;

    private static final int SHARED_SECRET_SIZE = 16;
    private static final int CHALLENGE_SIZE = 16;
    private static final int RESPONSE_SIZE = 32;
    private static final int ECDSA_SIG_SIZE = 64;
    private static final int RSA_SIG_SIZE = 100;

    public record CommandApdu(byte cla, byte ins, byte p1, byte p2, byte[] data) {}

    public record CreateRequestApdu(CommandApdu apdu, byte[] sharedSecret) {}

    public record ValidateResult(boolean valid, byte[] computedResponse) {}

    /**
     * Builds CREATE command: generates 16-byte SharedSecretDO, wraps in APDU.
     * SharedSecretDO size=16 enforced before encoding per FR-162.
     */
    public CreateRequestApdu buildCreateRequest() {
        byte[] sharedSecret = new byte[SHARED_SECRET_SIZE];
        new SecureRandom().nextBytes(sharedSecret);

        byte[] data = encodeSharedSecretDo(sharedSecret);
        CommandApdu apdu = new CommandApdu((byte) 0x80, INS_EHEALTH_AUTH, (byte) 0x00, P2_CREATE, data);
        LOG.debugf("[EhealthAuth] CREATE request built, SharedSecretDO size=%d", SHARED_SECRET_SIZE);
        return new CreateRequestApdu(apdu, sharedSecret);
    }

    /**
     * Builds VALIDATE command: challenge ≥16 bytes, expected response = SHA-256(challenge ‖ ShS.KT.AUT).
     */
    public CommandApdu buildValidateRequest() {
        byte[] challenge = new byte[CHALLENGE_SIZE];
        new SecureRandom().nextBytes(challenge);
        byte[] data = encodeChallengeDo(challenge);
        return new CommandApdu((byte) 0x80, INS_EHEALTH_AUTH, (byte) 0x00, P2_VALIDATE, data);
    }

    /**
     * Builds ADD Phase 1 request (Wartung — maintenance re-pairing).
     */
    public CommandApdu buildAddPhase1Request() {
        byte[] challenge = new byte[CHALLENGE_SIZE];
        new SecureRandom().nextBytes(challenge);
        byte[] data = encodeChallengeDo(challenge);
        return new CommandApdu((byte) 0x80, INS_EHEALTH_AUTH, (byte) 0x00, P2_ADD_PHASE1, data);
    }

    /**
     * Builds ADD Phase 2 request: response = SHA-256(terminalChallenge ‖ ShS.KT.AUT).
     */
    public CommandApdu buildAddPhase2Request(byte[] terminalChallenge, byte[] sharedSecret) throws Exception {
        if (terminalChallenge.length < CHALLENGE_SIZE) {
            throw new IllegalArgumentException(
                "SharedSecretChallengeDO MUST be ≥" + CHALLENGE_SIZE + " bytes, was " + terminalChallenge.length);
        }
        if (sharedSecret.length != SHARED_SECRET_SIZE) {
            throw new IllegalArgumentException(
                "SharedSecretDO MUST be exactly " + SHARED_SECRET_SIZE + " bytes, was " + sharedSecret.length);
        }
        byte[] response = computeResponse(terminalChallenge, sharedSecret);
        if (response.length != RESPONSE_SIZE) {
            throw new IllegalStateException("Response MUST be " + RESPONSE_SIZE + " bytes, was " + response.length);
        }
        byte[] data = encodeResponseDo(response);
        return new CommandApdu((byte) 0x80, INS_EHEALTH_AUTH, (byte) 0x00, P2_ADD_PHASE2, data);
    }

    /**
     * Parses CREATE response: ECDSA (64 bytes) or RSA (100 bytes) terminal signature.
     */
    public byte[] parseCreateResponse(byte[] responseApduBytes) {
        if (responseApduBytes == null || responseApduBytes.length < 2) {
            throw new IllegalArgumentException("CREATE response too short");
        }
        // Status word is last 2 bytes
        byte sw1 = responseApduBytes[responseApduBytes.length - 2];
        byte sw2 = responseApduBytes[responseApduBytes.length - 1];
        if (sw1 != (byte) 0x90 || sw2 != (byte) 0x00) {
            throw new IllegalStateException(String.format("CREATE failed SW=%02X%02X", sw1 & 0xFF, sw2 & 0xFF));
        }
        byte[] signature = Arrays.copyOf(responseApduBytes, responseApduBytes.length - 2);
        if (signature.length != ECDSA_SIG_SIZE && signature.length != RSA_SIG_SIZE) {
            throw new IllegalArgumentException(
                "CREATE response signature must be " + ECDSA_SIG_SIZE + " (ECDSA) or " +
                RSA_SIG_SIZE + " (RSA) bytes, was " + signature.length);
        }
        return signature;
    }

    /**
     * Parses ADD Phase 1 response: terminal challenge ≥16 bytes.
     */
    public byte[] parseAddPhase1Response(byte[] responseApduBytes) {
        if (responseApduBytes == null || responseApduBytes.length < 2 + CHALLENGE_SIZE) {
            throw new IllegalArgumentException(
                "ADD Phase 1 response too short: must be ≥" + (2 + CHALLENGE_SIZE) + " bytes");
        }
        byte sw1 = responseApduBytes[responseApduBytes.length - 2];
        byte sw2 = responseApduBytes[responseApduBytes.length - 1];
        if (sw1 != (byte) 0x90 || sw2 != (byte) 0x00) {
            throw new IllegalStateException(String.format("ADD Phase 1 failed SW=%02X%02X", sw1 & 0xFF, sw2 & 0xFF));
        }
        return Arrays.copyOf(responseApduBytes, responseApduBytes.length - 2);
    }

    private byte[] computeResponse(byte[] challenge, byte[] sharedSecret) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(challenge);
        sha256.update(sharedSecret);
        return sha256.digest();
    }

    private byte[] encodeSharedSecretDo(byte[] sharedSecret) {
        if (sharedSecret.length != SHARED_SECRET_SIZE) {
            throw new IllegalArgumentException(
                "SharedSecretDO MUST be exactly " + SHARED_SECRET_SIZE + " bytes, was " + sharedSecret.length);
        }
        // BER TLV: tag=82 (CONTEXT,PRIMITIVE,2), len=16, value
        return tlv((byte) 0x82, sharedSecret);
    }

    private byte[] encodeChallengeDo(byte[] challenge) {
        if (challenge.length < CHALLENGE_SIZE) {
            throw new IllegalArgumentException(
                "SharedSecretChallengeDO MUST be ≥" + CHALLENGE_SIZE + " bytes, was " + challenge.length);
        }
        return tlv((byte) 0x83, challenge);
    }

    private byte[] encodeResponseDo(byte[] response) {
        if (response.length != RESPONSE_SIZE) {
            throw new IllegalArgumentException(
                "SharedSecretResponseDO MUST be exactly " + RESPONSE_SIZE + " bytes, was " + response.length);
        }
        return tlv((byte) 0x84, response);
    }

    private byte[] tlv(byte tag, byte[] value) {
        byte[] result = new byte[2 + value.length];
        result[0] = tag;
        result[1] = (byte) value.length;
        System.arraycopy(value, 0, result, 2, value.length);
        return result;
    }
}
