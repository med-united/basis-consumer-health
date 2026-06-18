package de.servicehealtherx.crypto.sicct;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.CardCertificateException;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.card.transport.ReaderCapabilities;
import de.servicehealtherx.apdu.model.CardType;
import de.servicehealtherx.apdu.model.GematikISO7816;
import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
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
 * End-to-end test for the SICCT provider's card-backed read path: alias → CM_CARD_LIST → bound
 * terminal port → TUC_KON_216. Exercises the same {@code CardCertificateReadService} the PC/SC
 * provider delegates to, via the SICCT provider's {@code bindPortResolver} seam.
 */
class SicctCryptoProviderReadCertificateTest {

    private static final int SLOT = 1;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void reads_smcb_osig_certificate_for_card_alias() throws Exception {
        X509Certificate expected = selfSigned();
        FakePort port = scriptedCard(expected.getEncoded(), GematikISO7816.FID_EF_C_HCI_OSIG_E256);

        SicctCryptoProvider provider = new SicctCryptoProvider();
        String handle = provider.cmCardList().generateCardHandle();
        provider.cmCardList().add(CardObject.builder()
                .cardHandle(handle).ctid(port.ctid()).slotNo(SLOT).type(CardType.SMC_B).build());
        provider.bindPortResolver(ctid -> ctid.equals(port.ctid()) ? Optional.of(port) : Optional.empty());

        KeyAlias alias = new KeyAlias("sicct/" + handle);
        assertEquals(KeyStoreAvailability.AVAILABLE, provider.getAvailability(alias));
        assertEquals(expected, provider.readCertificate(alias, "C.SIG", "ECC"));
    }

    @Test
    void availability_is_unavailable_for_unknown_card() {
        SicctCryptoProvider provider = new SicctCryptoProvider();
        assertEquals(KeyStoreAvailability.UNAVAILABLE,
                provider.getAvailability(new KeyAlias("sicct/" + java.util.UUID.randomUUID())));
    }

    @Test
    void read_without_bound_terminal_fails() throws Exception {
        FakePort port = new FakePort("term");
        SicctCryptoProvider provider = new SicctCryptoProvider();
        String handle = provider.cmCardList().generateCardHandle();
        provider.cmCardList().add(CardObject.builder()
                .cardHandle(handle).ctid(port.ctid()).slotNo(SLOT).type(CardType.SMC_B).build());
        // No bindPortResolver — terminal not bound.

        assertThrows(CardCertificateException.class,
                () -> provider.readCertificate(new KeyAlias("sicct/" + handle), "C.AUT", "ECC"));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static FakePort scriptedCard(byte[] der, short efFid) {
        byte[] expectedFid = {(byte) (efFid >> 8), (byte) (efFid & 0xFF)};
        return new FakePort("sicct-term-1", (slot, cmd) -> {
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
    }

    private static ResponseAPDU sw(int sw) {
        return new ResponseAPDU(new byte[] {(byte) (sw >> 8), (byte) (sw & 0xFF)});
    }

    /** Minimal {@link CardReaderPort} test double scripting the APDU dialogue (no SICCT subsystem). */
    private static final class FakePort implements CardReaderPort {
        private final String name;
        private final UUID ctid;
        private final BiFunction<Integer, CommandAPDU, ResponseAPDU> responder;

        FakePort(String name) {
            this(name, (slot, cmd) -> new ResponseAPDU(new byte[] {(byte) 0x90, 0x00}));
        }

        FakePort(String name, BiFunction<Integer, CommandAPDU, ResponseAPDU> responder) {
            this.name = name;
            this.ctid = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.responder = responder;
        }

        @Override public String readerName() { return name; }
        @Override public UUID ctid() { return ctid; }
        @Override public ReaderCapabilities capabilities() { return ReaderCapabilities.sicctDefault(); }
        @Override public boolean isCardPresent(int slotNo) { return true; }
        @Override public ResponseAPDU transmit(int slotNo, CommandAPDU command) throws CardTransportException {
            return responder.apply(slotNo, command);
        }
        @Override public void addPresenceListener(PresenceListener listener) { }
        @Override public void removePresenceListener(PresenceListener listener) { }
    }

    private static X509Certificate selfSigned() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new java.security.spec.ECGenParameterSpec("brainpoolP256r1"));
        KeyPair kp = kpg.generateKeyPair();
        X500Name dn = new X500Name("CN=SMC-B OSIG Test");
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
