package de.servicehealtherx.crypto.ecies;

import de.servicehealtherx.crypto.ecies.jce.SoftwareElcDecryptor;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trips the gemSpec_COS ELC primitive: {@link ElcKeyWrapper} (ELC_ENC) wraps a transport key
 * that {@link SoftwareElcDecryptor} (ELC_DEC) recovers, proving the two directions are
 * self-consistent and follow the documented algorithm.
 */
class ElcKeyWrapperTest {

    private final ElcKeyWrapper wrapper = new ElcKeyWrapper();

    private static byte[] transportKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void wraps_and_unwraps_the_transport_key_brainpoolP256r1() {
        KeyPair kp = EciesTestKeys.brainpoolP256r1();
        byte[] m = transportKey();

        ElcCryptogram cg = wrapper.wrap(m, (ECPublicKey) kp.getPublic(), EciesOids.BRAINPOOL_P256R1);
        byte[] recovered = new SoftwareElcDecryptor((ECPrivateKey) kp.getPrivate()).unwrapTransportKey(cg);

        assertArrayEquals(m, recovered);
    }

    @Test
    void produces_a_well_formed_cryptogram() {
        KeyPair kp = EciesTestKeys.brainpoolP256r1();

        ElcCryptogram cg = wrapper.wrap(transportKey(), (ECPublicKey) kp.getPublic(), EciesOids.BRAINPOOL_P256R1);

        assertEquals(EciesOids.BRAINPOOL_P256R1, cg.curveOid());
        assertEquals(65, cg.po().length, "uncompressed brainpoolP256r1 point");
        assertEquals(0x04, cg.po()[0] & 0xFF);
        assertEquals(48, cg.c().length, "32-byte key ISO-padded to 48 then AES-CBC");
        assertEquals(8, cg.t().length, "truncated CMAC tag");
    }

    @Test
    void survives_the_wire_codec_round_trip() {
        KeyPair kp = EciesTestKeys.brainpoolP256r1();
        byte[] m = transportKey();

        ElcCryptogram wrapped = wrapper.wrap(m, (ECPublicKey) kp.getPublic(), EciesOids.BRAINPOOL_P256R1);
        ElcCryptogram afterWire = ElcCryptogram.parse(wrapped.toAsn1());
        byte[] recovered = new SoftwareElcDecryptor((ECPrivateKey) kp.getPrivate()).unwrapTransportKey(afterWire);

        assertArrayEquals(m, recovered);
    }

    @Test
    void rejects_a_tampered_ciphertext_via_mac() {
        KeyPair kp = EciesTestKeys.brainpoolP256r1();
        ElcCryptogram cg = wrapper.wrap(transportKey(), (ECPublicKey) kp.getPublic(), EciesOids.BRAINPOOL_P256R1);

        byte[] tamperedC = cg.c();
        tamperedC[0] ^= 0x01;
        ElcCryptogram tampered = new ElcCryptogram(cg.curveOid(), cg.po(), tamperedC, cg.t());

        SoftwareElcDecryptor decryptor = new SoftwareElcDecryptor((ECPrivateKey) kp.getPrivate());
        assertThrows(SecurityException.class, () -> decryptor.unwrapTransportKey(tampered));
    }

    @Test
    void unwrapping_with_the_wrong_key_fails() {
        KeyPair sender = EciesTestKeys.brainpoolP256r1();
        KeyPair other = EciesTestKeys.brainpoolP256r1();
        ElcCryptogram cg = wrapper.wrap(transportKey(), (ECPublicKey) sender.getPublic(), EciesOids.BRAINPOOL_P256R1);

        SoftwareElcDecryptor wrongKey = new SoftwareElcDecryptor((ECPrivateKey) other.getPrivate());
        assertThrows(SecurityException.class, () -> wrongKey.unwrapTransportKey(cg));
    }
}
