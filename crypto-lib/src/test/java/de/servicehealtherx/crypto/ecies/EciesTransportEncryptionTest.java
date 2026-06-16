package de.servicehealtherx.crypto.ecies;

import de.servicehealtherx.crypto.ecies.jce.ElcSecurityProvider;
import org.bouncycastle.asn1.cms.AuthEnvelopedData;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.KeyTransRecipientInfo;
import org.bouncycastle.asn1.cms.RecipientInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end round-trip for the CMS {@code AuthEnvelopedData} assembly: single and multi-recipient
 * encryption, the mandated key-encryption OID, and negative cases (FR-001/002/008/010, SC-008).
 */
class EciesTransportEncryptionTest {

    private final EciesTransportEncryption ecies = new EciesTransportEncryption();
    private final Provider elcProvider = new ElcSecurityProvider();
    private final byte[] document = "Patient document — confidential".getBytes(StandardCharsets.UTF_8);

    private record Recipient(X509Certificate cert, KeyPair keyPair) {
        static Recipient brainpool(String cn) {
            KeyPair kp = EciesTestKeys.brainpoolP256r1();
            return new Recipient(EciesTestKeys.selfSigned(kp, cn), kp);
        }
    }

    @Test
    void single_recipient_round_trip() {
        Recipient r = Recipient.brainpool("egk-holder");

        byte[] envelope = ecies.encrypt(document, List.of(r.cert()));
        byte[] recovered = ecies.decrypt(envelope, r.cert(), r.keyPair().getPrivate(), elcProvider);

        assertArrayEquals(document, recovered);
    }

    @Test
    void every_recipient_carries_the_mandated_oid() throws Exception {
        Recipient r = Recipient.brainpool("egk-holder");

        byte[] envelope = ecies.encrypt(document, List.of(r.cert()));

        AuthEnvelopedData aed = AuthEnvelopedData.getInstance(
                ContentInfo.getInstance(envelope).getContent());
        KeyTransRecipientInfo ktri = (KeyTransRecipientInfo)
                RecipientInfo.getInstance(aed.getRecipientInfos().getObjectAt(0)).getInfo();
        assertEquals(EciesOids.TI_ECIES_TRANSPORT_ENCRYPTION,
                ktri.getKeyEncryptionAlgorithm().getAlgorithm());
        assertEquals(CMSObjectIdentifiers.authEnvelopedData,
                ContentInfo.getInstance(envelope).getContentType());
    }

    @Test
    void multi_recipient_each_decrypts_independently() {
        Recipient a = Recipient.brainpool("insured-person");
        Recipient b = Recipient.brainpool("practice");
        Recipient c = Recipient.brainpool("hospital");

        byte[] envelope = ecies.encrypt(document, List.of(a.cert(), b.cert(), c.cert()));

        assertArrayEquals(document, ecies.decrypt(envelope, a.cert(), a.keyPair().getPrivate(), elcProvider));
        assertArrayEquals(document, ecies.decrypt(envelope, b.cert(), b.keyPair().getPrivate(), elcProvider));
        assertArrayEquals(document, ecies.decrypt(envelope, c.cert(), c.keyPair().getPrivate(), elcProvider));
    }

    @Test
    void two_encryptions_differ_but_both_decrypt() {
        Recipient r = Recipient.brainpool("egk-holder");

        byte[] e1 = ecies.encrypt(document, List.of(r.cert()));
        byte[] e2 = ecies.encrypt(document, List.of(r.cert()));

        assertThrows(AssertionError.class, () -> assertArrayEquals(e1, e2));
        assertArrayEquals(document, ecies.decrypt(e1, r.cert(), r.keyPair().getPrivate(), elcProvider));
        assertArrayEquals(document, ecies.decrypt(e2, r.cert(), r.keyPair().getPrivate(), elcProvider));
    }

    @Test
    void empty_document_round_trips() {
        Recipient r = Recipient.brainpool("egk-holder");

        byte[] envelope = ecies.encrypt(new byte[0], List.of(r.cert()));
        byte[] recovered = ecies.decrypt(envelope, r.cert(), r.keyPair().getPrivate(), elcProvider);

        assertArrayEquals(new byte[0], recovered);
    }

    @Test
    void rejects_empty_recipient_list() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ecies.encrypt(document, List.of()));
        assertEquals("no recipient", ex.getMessage());
    }

    @Test
    void rejects_rsa_recipient() {
        KeyPair rsa = EciesTestKeys.rsa2048();
        X509Certificate rsaCert = EciesTestKeys.selfSigned(rsa, "rsa-recipient");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ecies.encrypt(document, List.of(rsaCert)));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("unsuitable recipient"));
    }

    @Test
    void rejects_decryption_with_non_recipient_key() {
        Recipient r = Recipient.brainpool("egk-holder");
        Recipient stranger = Recipient.brainpool("stranger");
        byte[] envelope = ecies.encrypt(document, List.of(r.cert()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ecies.decrypt(envelope, stranger.cert(), stranger.keyPair().getPrivate(), elcProvider));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("no matching key"));
    }
}
