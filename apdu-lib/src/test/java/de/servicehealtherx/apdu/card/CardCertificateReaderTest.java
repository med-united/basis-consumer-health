package de.servicehealtherx.apdu.card;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.FakeCardReaderPort;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.CertificateRef;
import de.servicehealtherx.apdu.model.GematikISO7816;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Drives {@link CardCertificateReader} over a {@link FakeCardReaderPort} scripted with the
 * SELECT DF.ESIGN → SELECT EF → READ BINARY dialogue, returning a real DER certificate in
 * ≤256-byte blocks — the same double both transports run against.
 */
class CardCertificateReaderTest {

    private static final int SLOT = 1;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void reads_smcb_aut_ecc_certificate_across_blocks() throws Exception {
        X509Certificate expected = selfSigned();
        byte[] der = expected.getEncoded();
        FakeCardReaderPort port = scriptedCard(der, GematikISO7816.FID_EF_C_HCI_AUT_E256);

        X509Certificate read = new CardCertificateReader()
                .readCertificate(port, SLOT, "handle-1", CardType.SMC_B, CertificateRef.C_AUT, true);

        assertEquals(expected, read);
    }

    @Test
    void reads_smcb_osig_rsa_certificate() throws Exception {
        X509Certificate expected = selfSigned();
        FakeCardReaderPort port = scriptedCard(expected.getEncoded(), GematikISO7816.FID_EF_C_HCI_OSIG_R2048);

        X509Certificate read = new CardCertificateReader()
                .readCertificate(port, SLOT, "handle-2", CardType.SMC_B, CertificateRef.C_SIG, false);

        assertEquals(expected, read);
    }

    @Test
    void rejects_egk_read() {
        FakeCardReaderPort port = FakeCardReaderPort.sicct("term");
        port.simulateInsert(SLOT);

        assertThrows(CardCertificateException.class, () -> new CardCertificateReader()
                .readCertificate(port, SLOT, "egk-handle", CardType.EGK, CertificateRef.C_AUT, true));
    }

    @Test
    void throws_when_certificate_absent_on_card() throws Exception {
        // SELECT DF.ESIGN succeeds, SELECT EF returns "file not found".
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader");
        port.simulateInsert(SLOT);
        port.setResponder((slot, cmd) -> {
            if (isSelectEf(cmd)) {
                return sw(GematikISO7816.SW_OBJECT_NOT_FOUND);
            }
            return sw(GematikISO7816.SW_SUCCESS);
        });

        CardCertificateException ex = assertThrows(CardCertificateException.class, () -> new CardCertificateReader()
                .readCertificate(port, SLOT, "handle-3", CardType.HBA, CertificateRef.C_AUT, true));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("not present"));
    }

    @Test
    void rejects_unsupported_card_type_for_osig() {
        assertThrows(IllegalArgumentException.class,
                () -> de.servicehealtherx.apdu.tuc.TucKon216ReadCertificate
                        .fileIdentifierFor(CardType.HBA, CertificateRef.C_SIG, true));
    }

    // --- scripting helpers --------------------------------------------------------------------

    /** A card answering SELECT DF.ESIGN + SELECT {@code efFid} OK and serving {@code der} via READ BINARY. */
    private static FakeCardReaderPort scriptedCard(byte[] der, short efFid) {
        FakeCardReaderPort port = FakeCardReaderPort.pcsc("reader");
        port.simulateInsert(SLOT);
        byte[] expectedFid = {(byte) (efFid >> 8), (byte) (efFid & 0xFF)};
        port.setResponder((slot, cmd) -> {
            if (cmd.getINS() == GematikISO7816.INS_SELECT && cmd.getP1() == GematikISO7816.SELECT_BY_DF_NAME) {
                return sw(GematikISO7816.SW_SUCCESS); // SELECT DF.ESIGN
            }
            if (isSelectEf(cmd)) {
                return Arrays.equals(cmd.getData(), expectedFid)
                        ? sw(GematikISO7816.SW_SUCCESS)
                        : sw(GematikISO7816.SW_OBJECT_NOT_FOUND);
            }
            if (cmd.getINS() == GematikISO7816.INS_READ_BINARY) {
                int offset = (cmd.getP1() << 8) | cmd.getP2();
                int remaining = der.length - offset;
                if (remaining <= 0) {
                    return sw(GematikISO7816.SW_SUCCESS); // empty → loop stops
                }
                int n = Math.min(256, remaining);
                byte[] body = new byte[n + 2];
                System.arraycopy(der, offset, body, 0, n);
                body[n] = (byte) 0x90;
                body[n + 1] = 0x00;
                return new ResponseAPDU(body);
            }
            return sw(GematikISO7816.SW_SUCCESS);
        });
        return port;
    }

    private static boolean isSelectEf(CommandAPDU cmd) {
        return cmd.getINS() == GematikISO7816.INS_SELECT && cmd.getP1() == GematikISO7816.SELECT_BY_FILE_ID;
    }

    private static ResponseAPDU sw(int sw) {
        return new ResponseAPDU(new byte[] {(byte) (sw >> 8), (byte) (sw & 0xFF)});
    }

    private static X509Certificate selfSigned() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new java.security.spec.ECGenParameterSpec("brainpoolP256r1"));
        KeyPair kp = kpg.generateKeyPair();
        X500Name dn = new X500Name("CN=SMC-B Test,OU=Org");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(System.nanoTime()),
                Date.from(now), Date.from(now.plusSeconds(3600)), dn, kp.getPublic());
        X509CertificateHolder holder = builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(kp.getPrivate()));
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder);
    }
}
