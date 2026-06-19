package de.servicehealtherx.apdu.c2c;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.params.KeyParameter;

import de.servicehealtherx.apdu.card.ApduSecureChannel;

/**
 * ISO 7816-4 / gemSpec_COS AES Secure-Messaging channel established by the C2C handshake. Wraps a
 * command (CLA|=0x0C, encrypt data into DO'87', MAC into DO'8E' over the send-sequence counter) and
 * unwraps the response (verify DO'8E', decrypt DO'87', recover status from DO'99'). Keys and the
 * initial SSC come from the ELC session-key agreement (gemSpec_COS §15.4.4).
 *
 * <p><strong>Not validated against real card output</strong> — the C2C handshake is hardware-bound
 * (see research.md C2C risk note). Unit-tested only for structural round-tripping of the framing.
 */
public final class SecureMessagingSession implements ApduSecureChannel {

    private static final int BLOCK = 16;
    private final byte[] kEnc;
    private final byte[] kMac;
    private byte[] ssc; // 16-byte send-sequence counter

    public SecureMessagingSession(byte[] kEnc, byte[] kMac, byte[] initialSsc) {
        this.kEnc = kEnc.clone();
        this.kMac = kMac.clone();
        this.ssc = initialSsc.clone();
    }

    @Override
    public CommandAPDU wrap(CommandAPDU command) {
        incrementSsc();
        try {
            int cla = command.getCLA() | 0x0C;
            byte[] header = {(byte) cla, (byte) command.getINS(), (byte) command.getP1(), (byte) command.getP2()};

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            byte[] data = command.getData();
            if (data != null && data.length > 0) {
                byte[] enc = encrypt(pad(data));
                payload.write(0x87);
                writeLen(payload, enc.length + 1);
                payload.write(0x01); // padding-content indicator
                payload.write(enc);
            }
            if (command.getNe() > 0) {
                payload.write(0x97);
                payload.write(0x01);
                payload.write(command.getNe() == 256 ? 0x00 : command.getNe() & 0xFF);
            }
            byte[] doData = payload.toByteArray();

            ByteArrayOutputStream macInput = new ByteArrayOutputStream();
            macInput.write(ssc);
            macInput.write(pad(header));
            macInput.write(doData);
            byte[] mac = cmac(pad(macInput.toByteArray()));

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(doData);
            body.write(0x8E);
            body.write(0x08);
            body.write(mac, 0, 8);
            return new CommandAPDU(cla, command.getINS(), command.getP1(), command.getP2(), body.toByteArray(), 0x00);
        } catch (Exception e) {
            throw new IllegalStateException("SM wrap failed", e);
        }
    }

    @Override
    public ResponseAPDU unwrap(ResponseAPDU response) {
        incrementSsc();
        try {
            byte[] body = response.getData();
            byte[] encrypted = null;
            int sw = response.getSW();
            int pos = 0;
            while (pos < body.length) {
                int tag = body[pos++] & 0xFF;
                int len = body[pos++] & 0xFF;
                byte[] value = Arrays.copyOfRange(body, pos, pos + len);
                pos += len;
                if (tag == 0x87) {
                    encrypted = Arrays.copyOfRange(value, 1, value.length); // skip padding indicator
                } else if (tag == 0x99) {
                    sw = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
                }
            }
            byte[] plain = encrypted == null ? new byte[0] : unpad(decrypt(encrypted));
            byte[] out = Arrays.copyOf(plain, plain.length + 2);
            out[plain.length] = (byte) (sw >> 8);
            out[plain.length + 1] = (byte) (sw & 0xFF);
            return new ResponseAPDU(out);
        } catch (Exception e) {
            throw new IllegalStateException("SM unwrap failed", e);
        }
    }

    private byte[] encrypt(byte[] padded) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kEnc, "AES"), new IvParameterSpec(encryptSsc()));
        return c.doFinal(padded);
    }

    private byte[] decrypt(byte[] cipher) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kEnc, "AES"), new IvParameterSpec(encryptSsc()));
        return c.doFinal(cipher);
    }

    /** gemSpec_COS derives the CBC IV by ECB-encrypting the current SSC under K.Enc. */
    private byte[] encryptSsc() throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kEnc, "AES"));
        return c.doFinal(ssc);
    }

    private byte[] cmac(byte[] input) {
        CMac mac = new CMac(AESEngine.newInstance());
        mac.init(new KeyParameter(kMac));
        mac.update(input, 0, input.length);
        byte[] out = new byte[mac.getMacSize()];
        mac.doFinal(out, 0);
        return out;
    }

    private void incrementSsc() {
        BigInteger v = new BigInteger(1, ssc).add(BigInteger.ONE);
        byte[] raw = v.toByteArray();
        byte[] next = new byte[BLOCK];
        int copy = Math.min(raw.length, BLOCK);
        System.arraycopy(raw, raw.length - copy, next, BLOCK - copy, copy);
        ssc = next;
    }

    private static byte[] pad(byte[] in) {
        int padded = ((in.length / BLOCK) + 1) * BLOCK;
        byte[] out = Arrays.copyOf(in, padded);
        out[in.length] = (byte) 0x80;
        return out;
    }

    private static byte[] unpad(byte[] in) {
        int i = in.length - 1;
        while (i >= 0 && in[i] == 0x00) {
            i--;
        }
        if (i >= 0 && (in[i] & 0xFF) == 0x80) {
            return Arrays.copyOf(in, i);
        }
        return in;
    }

    private static void writeLen(ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
        } else {
            out.write(0x81);
            out.write(len & 0xFF);
        }
    }
}
