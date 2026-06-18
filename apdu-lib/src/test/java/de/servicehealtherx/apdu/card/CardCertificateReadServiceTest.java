package de.servicehealtherx.apdu.card;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.FakeCardReaderPort;
import de.servicehealtherx.apdu.model.CardType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end orchestration test for {@link CardCertificateReadService}: handle → CM_CARD_LIST →
 * resolved {@link CardReaderPort} → TUC_KON_216 read. This is the shared path the PC/SC and SICCT
 * providers both delegate to.
 */
class CardCertificateReadServiceTest {

    private static final int SLOT = 2;
    private static final String READER = "reader-A";

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void reads_certificate_for_a_known_handle() throws Exception {
        X509Certificate expected = selfSigned();
        FakeCardReaderPort port = scriptedCard(expected.getEncoded(), GematikISO7816.FID_EF_C_HCI_AUT_E256);

        CmCardList list = new CmCardList();
        String handle = addCard(list, port, CardType.SMC_B);
        CardCertificateReadService service = new CardCertificateReadService(list, resolver(port));

        assertTrue(service.hasCard(handle));
        assertEquals(expected, service.readCertificate(handle, "C.AUT", "ECC"));
    }

    @Test
    void unknown_handle_is_rejected() {
        CardCertificateReadService service =
                new CardCertificateReadService(new CmCardList(), CardReaderPortResolver.NONE);

        CardCertificateException ex = assertThrows(CardCertificateException.class,
                () -> service.readCertificate("nope", "C.AUT", "ECC"));
        assertTrue(ex.getMessage().contains("Unknown card handle"));
    }

    @Test
    void missing_terminal_binding_is_rejected() {
        FakeCardReaderPort port = FakeCardReaderPort.sicct(READER);
        CmCardList list = new CmCardList();
        String handle = addCard(list, port, CardType.SMC_B);
        // Resolver knows no ports — models a card known to the list but no live terminal bound.
        CardCertificateReadService service = new CardCertificateReadService(list, CardReaderPortResolver.NONE);

        CardCertificateException ex = assertThrows(CardCertificateException.class,
                () -> service.readCertificate(handle, "C.AUT", "ECC"));
        assertTrue(ex.getMessage().contains("No card terminal bound"));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static CardReaderPortResolver resolver(CardReaderPort port) {
        return ctid -> ctid.equals(port.ctid()) ? Optional.of(port) : Optional.empty();
    }

    private static String addCard(CmCardList list, CardReaderPort port, CardType type) {
        String handle = list.generateCardHandle();
        list.add(CardObject.builder()
                .cardHandle(handle).ctid(port.ctid()).slotNo(SLOT).type(type).build());
        return handle;
    }

    private static FakeCardReaderPort scriptedCard(byte[] der, short efFid) {
        FakeCardReaderPort port = FakeCardReaderPort.sicct(READER);
        port.simulateInsert(SLOT);
        byte[] expectedFid = {(byte) (efFid >> 8), (byte) (efFid & 0xFF)};
        port.setResponder((slot, cmd) -> {
            if (cmd.getINS() == GematikISO7816.INS_SELECT && cmd.getP1() == GematikISO7816.SELECT_BY_DF_NAME) {
                return sw(GematikISO7816.SW_SUCCESS);
            }
            if (cmd.getINS() == GematikISO7816.INS_SELECT && cmd.getP1() == GematikISO7816.SELECT_BY_FILE_ID) {
                return Arrays.equals(cmd.getData(), expectedFid)
                        ? sw(GematikISO7816.SW_SUCCESS) : sw(GematikISO7816.SW_OBJECT_NOT_FOUND);
            }
            if (cmd.getINS() == GematikISO7816.INS_READ_BINARY) {
                int offset = (cmd.getP1() << 8) | cmd.getP2();
                int remaining = der.length - offset;
                if (remaining <= 0) {
                    return sw(GematikISO7816.SW_SUCCESS);
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

    private static ResponseAPDU sw(int sw) {
        return new ResponseAPDU(new byte[] {(byte) (sw >> 8), (byte) (sw & 0xFF)});
    }

    private static X509Certificate selfSigned() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new java.security.spec.ECGenParameterSpec("brainpoolP256r1"));
        KeyPair kp = kpg.generateKeyPair();
        X500Name dn = new X500Name("CN=SMC-B Test");
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
