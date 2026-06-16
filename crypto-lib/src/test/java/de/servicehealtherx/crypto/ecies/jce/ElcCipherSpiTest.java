package de.servicehealtherx.crypto.ecies.jce;

import de.servicehealtherx.crypto.ecies.EciesOids;
import de.servicehealtherx.crypto.ecies.EciesTestKeys;
import de.servicehealtherx.crypto.ecies.ElcCryptogram;
import de.servicehealtherx.crypto.ecies.ElcKeyWrapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies decryption through the standard {@code javax.crypto.Cipher("ELC")} seam, for both a
 * software EC key and a card-backed {@link CardElcPrivateKey} — the caller-facing abstraction
 * mandated by FR-015.
 */
class ElcCipherSpiTest {

    private final ElcSecurityProvider provider = new ElcSecurityProvider();
    private final ElcKeyWrapper wrapper = new ElcKeyWrapper();

    private static byte[] transportKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void decrypts_a_software_key_via_jce_cipher() throws Exception {
        KeyPair kp = EciesTestKeys.brainpoolP256r1();
        byte[] m = transportKey();
        ElcCryptogram cg = wrapper.wrap(m, (ECPublicKey) kp.getPublic(), EciesOids.BRAINPOOL_P256R1);

        Cipher cipher = Cipher.getInstance("ELC", provider);
        cipher.init(Cipher.DECRYPT_MODE, kp.getPrivate());
        byte[] recovered = cipher.doFinal(cg.toAsn1());

        assertArrayEquals(m, recovered);
    }

    @Test
    void decrypts_a_card_key_via_the_same_seam() throws Exception {
        KeyPair kp = EciesTestKeys.brainpoolP256r1();
        byte[] m = transportKey();
        ElcCryptogram cg = wrapper.wrap(m, (ECPublicKey) kp.getPublic(), EciesOids.BRAINPOOL_P256R1);

        // A fake card decryptor standing in for a PSO:DECIPHER round-trip (no hardware in the test).
        ElcDecryptor fakeCard = new SoftwareElcDecryptor((ECPrivateKey) kp.getPrivate());
        CardElcPrivateKey cardKey = new CardElcPrivateKey("pcsc/egk/enc", fakeCard);

        Cipher cipher = Cipher.getInstance("ELC", provider);
        cipher.init(Cipher.DECRYPT_MODE, cardKey);
        byte[] recovered = cipher.doFinal(cg.toAsn1());

        assertArrayEquals(m, recovered);
    }

    @Test
    void rejects_encryption_mode() throws Exception {
        KeyPair kp = EciesTestKeys.brainpoolP256r1();
        Cipher cipher = Cipher.getInstance("ELC", provider);

        assertThrows(UnsupportedOperationException.class,
                () -> cipher.init(Cipher.ENCRYPT_MODE, kp.getPublic()));
    }
}
