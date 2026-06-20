package de.servicehealtherx.konnektor.vsdm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.c2c.CardToCardAuthenticator;
import de.servicehealtherx.apdu.card.ApduSecureChannel;
import de.servicehealtherx.apdu.card.CardObject;
import de.servicehealtherx.apdu.card.CertStatus;
import de.servicehealtherx.apdu.card.CmCardList;
import de.servicehealtherx.apdu.card.OcspResult;
import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardReaderPortResolver;
import de.servicehealtherx.apdu.card.transport.ScriptedCardReaderPort;
import de.servicehealtherx.apdu.model.CardType;

class ReadVsdServiceTest {

    // gemSpec_eGK_Fach_VSDM Tab_eGK_Fach_VSDM_04: [0] Status '0' (ASCII), [1..14] Timestamp
    // "20120131084713" (ASCII), [15..19] Version_XML BCD 0070030001, [20..24] Version_Speicherstruktur
    // BCD 0030000004.
    private static final byte[] STATUS_VD = {
            '0',
            '2', '0', '1', '2', '0', '1', '3', '1', '0', '8', '4', '7', '1', '3',
            0x00, 0x70, 0x03, 0x00, 0x01,
            0x00, 0x30, 0x00, 0x00, 0x04
    };
    // Deliverable gzip payloads (what the ReadVSDResponse must carry) and their on-card EF framing
    // (gemSpec_eGK_ObjSys_G2_1 §5.4): EF.PD/EF.GVD = 2-byte length prefix + gzip; EF.VD = 8-byte
    // start/end offset table + gzip. ReadVsdService must de-frame these back to the bare gzip stream.
    private static final byte[] PD_PAYLOAD = gzip("PERSONAL");
    private static final byte[] VD_PAYLOAD = gzip("GENERAL");
    private static final byte[] PD_FILE = lengthPrefixed(PD_PAYLOAD);
    private static final byte[] VD_FILE = vdFramed(VD_PAYLOAD);
    private final UUID ctid = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** A minimal gzip stream (starts with the 1f 8b magic) so de-framing can be validated. */
    private static byte[] gzip(String content) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    /** EF.PD / EF.GVD framing: 2-byte big-endian length prefix + gzip. */
    private static byte[] lengthPrefixed(byte[] gz) {
        byte[] out = new byte[gz.length + 2];
        out[0] = (byte) (gz.length >>> 8);
        out[1] = (byte) gz.length;
        System.arraycopy(gz, 0, out, 2, gz.length);
        return out;
    }

    /** EF.VD framing: 8-byte offset header [startAVD][endAVD][startGVD][endGVD] + data (empty GVD). */
    private static byte[] vdFramed(byte[] avdGz) {
        int start = 8;
        int end = start + avdGz.length;
        byte[] out = new byte[end];
        out[0] = (byte) (start >>> 8); out[1] = (byte) start;   // start AVD
        out[2] = (byte) (end >>> 8);   out[3] = (byte) end;     // end AVD
        out[4] = (byte) (end >>> 8);   out[5] = (byte) end;     // start GVD (transitional copy, empty)
        out[6] = (byte) (end >>> 8);   out[7] = (byte) end;     // end GVD
        System.arraycopy(avdGz, 0, out, 8, avdGz.length);
        return out;
    }

    private List<byte[]> mandatoryReadScript() {
        List<byte[]> r = new ArrayList<>();
        r.add(ScriptedCardReaderPort.ok());                       // SELECT DF.HCA
        r.add(ScriptedCardReaderPort.ok());                       // SELECT EF.StatusVD
        r.add(ScriptedCardReaderPort.resp(STATUS_VD, 0x9000));    // READ EF.StatusVD
        r.add(ScriptedCardReaderPort.ok());                       // SELECT EF.PD
        r.add(ScriptedCardReaderPort.resp(PD_FILE, 0x9000));      // READ EF.PD
        r.add(ScriptedCardReaderPort.ok());                       // SELECT EF.VD
        r.add(ScriptedCardReaderPort.resp(VD_FILE, 0x9000));      // READ EF.VD
        return r;
    }

    private CmCardList cardListWithEgkAndSmcb() {
        CmCardList list = new CmCardList();
        list.add(CardObject.builder().cardHandle("egk").ctid(ctid).slotNo(1).type(CardType.EGK).build());
        list.add(CardObject.builder().cardHandle("smcb").ctid(ctid).slotNo(2).type(CardType.SMC_B).build());
        return list;
    }

    private CardReaderPortResolver resolverFor(CardReaderPort port) {
        return id -> id.equals(ctid) ? Optional.of(port) : Optional.empty();
    }

    private ReadVsdRequest validRequest() {
        return new ReadVsdRequest("egk", "smcb", false, false, "m1", "c1", "w1", null);
    }

    @Test
    void test_VSDM_A_2567_returns_pd_vd_and_status_and_omits_gvd_when_not_authorised() {
        var port = new ScriptedCardReaderPort(ctid, mandatoryReadScript());
        var service = new ReadVsdService(cardListWithEgkAndSmcb(), resolverFor(port),
                CardToCardAuthenticator.NONE, 30_000);

        VsdReadResult result = service.read(validRequest());

        assertArrayEquals(PD_PAYLOAD, result.personalData(), "EF.PD length prefix must be stripped");
        assertArrayEquals(VD_PAYLOAD, result.generalData(), "EF.VD AVD must be sliced by its offsets");
        assertEquals("0", result.status().status());
        assertEquals("7.3.1", result.status().version());
        assertTrue(result.protectedData().isEmpty(), "GVD omitted when C2C did not authorise it (FR-021)");
    }

    @Test
    void test_FR_007_rejects_perform_online_check() {
        var service = new ReadVsdService(cardListWithEgkAndSmcb(),
                resolverFor(new ScriptedCardReaderPort(ctid, List.of())), CardToCardAuthenticator.NONE, 30_000);
        var req = new ReadVsdRequest("egk", "smcb", true, false, "m1", "c1", "w1", null);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(req));
        assertEquals(VsdmErrorCode.ONLINE_CHECK_NOT_SUPPORTED, ex.errorCode());
    }

    @Test
    void test_FR_008_rejects_read_online_receipt() {
        var service = new ReadVsdService(cardListWithEgkAndSmcb(),
                resolverFor(new ScriptedCardReaderPort(ctid, List.of())), CardToCardAuthenticator.NONE, 30_000);
        var req = new ReadVsdRequest("egk", "smcb", false, true, "m1", "c1", "w1", null);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(req));
        assertEquals(VsdmErrorCode.RECEIPT_NOT_SUPPORTED, ex.errorCode());
    }

    @Test
    void test_VSDM_A_2660_aborts_3001_on_inconsistent_status() {
        List<byte[]> script = new ArrayList<>();
        script.add(ScriptedCardReaderPort.ok());                       // SELECT DF.HCA
        script.add(ScriptedCardReaderPort.ok());                       // SELECT EF.StatusVD
        byte[] inconsistent = STATUS_VD.clone();
        inconsistent[0] = '1'; // Status = '1' → open transactions (offset 0, ASCII)
        script.add(ScriptedCardReaderPort.resp(inconsistent, 0x9000)); // READ EF.StatusVD
        var port = new ScriptedCardReaderPort(ctid, script);
        var service = new ReadVsdService(cardListWithEgkAndSmcb(), resolverFor(port),
                CardToCardAuthenticator.NONE, 30_000);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(validRequest()));
        assertEquals(VsdmErrorCode.VSD_INCONSISTENT, ex.errorCode());
    }

    @Test
    void test_FR_027_aborts_on_timeout() {
        var port = new ScriptedCardReaderPort(ctid, mandatoryReadScript());
        port.onBeforeTransmit(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var service = new ReadVsdService(cardListWithEgkAndSmcb(), resolverFor(port),
                CardToCardAuthenticator.NONE, 20);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(validRequest()));
        assertEquals(VsdmErrorCode.TIMEOUT, ex.errorCode());
    }

    /** An authenticator that authorises GVD with a passthrough channel (SM tested separately). */
    private static final CardToCardAuthenticator AUTHORISES_GVD =
            (resolver, egk, hpc) -> Optional.of(ApduSecureChannel.NONE);

    @Test
    void test_VSDM_A_2574_returns_gvd_and_writes_audit_when_authorised() {
        byte[] gvdPayload = gzip("PROTECTED");
        List<byte[]> script = mandatoryReadScript();
        script.add(ScriptedCardReaderPort.ok());                                  // SELECT DF.HCA (re-select after C2C)
        script.add(ScriptedCardReaderPort.ok());                                  // SELECT EF.GVD
        script.add(ScriptedCardReaderPort.resp(lengthPrefixed(gvdPayload), 0x9000)); // READ EF.GVD
        script.add(ScriptedCardReaderPort.ok());                                  // APPEND RECORD (audit)
        var port = new ScriptedCardReaderPort(ctid, script);
        var service = new ReadVsdService(cardListWithEgkAndSmcb(), resolverFor(port), AUTHORISES_GVD, 30_000);

        VsdReadResult result = service.read(validRequest());
        assertTrue(result.protectedData().isPresent());
        assertArrayEquals(gvdPayload, result.protectedData().get(), "EF.GVD length prefix must be stripped");
    }

    @Test
    void test_VSDM_A_2654_aborts_when_audit_write_fails() {
        List<byte[]> script = mandatoryReadScript();
        script.add(ScriptedCardReaderPort.ok());                                       // SELECT DF.HCA (re-select after C2C)
        script.add(ScriptedCardReaderPort.ok());                                       // SELECT EF.GVD
        script.add(ScriptedCardReaderPort.resp(lengthPrefixed(gzip("PROTECTED")), 0x9000)); // READ EF.GVD
        script.add(ScriptedCardReaderPort.resp(null, 0x6A82));                         // APPEND RECORD fails
        var port = new ScriptedCardReaderPort(ctid, script);
        var service = new ReadVsdService(cardListWithEgkAndSmcb(), resolverFor(port), AUTHORISES_GVD, 30_000);

        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(validRequest()));
        assertEquals(VsdmErrorCode.VSD_READ_FAILED, ex.errorCode());
    }

    @Test
    void test_FR_015_aborts_106_when_egk_certificate_revoked() {
        CmCardList list = new CmCardList();
        CardObject egk = CardObject.builder().cardHandle("egk").ctid(ctid).slotNo(1).type(CardType.EGK).build();
        egk.setCertOcspResponse(OcspResult.REVOKED);
        list.add(egk);
        list.add(CardObject.builder().cardHandle("smcb").ctid(ctid).slotNo(2).type(CardType.SMC_B).build());
        var service = new ReadVsdService(list, resolverFor(new ScriptedCardReaderPort(ctid, List.of())),
                CardToCardAuthenticator.NONE, 30_000);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(validRequest()));
        assertEquals(VsdmErrorCode.EGK_CERT_REVOKED, ex.errorCode());
    }

    @Test
    void test_FR_015_aborts_107_when_egk_certificate_invalid() {
        CmCardList list = new CmCardList();
        CardObject egk = CardObject.builder().cardHandle("egk").ctid(ctid).slotNo(1).type(CardType.EGK).build();
        egk.setCertStatus(CertStatus.INVALID);
        list.add(egk);
        list.add(CardObject.builder().cardHandle("smcb").ctid(ctid).slotNo(2).type(CardType.SMC_B).build());
        var service = new ReadVsdService(list, resolverFor(new ScriptedCardReaderPort(ctid, List.of())),
                CardToCardAuthenticator.NONE, 30_000);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(validRequest()));
        assertEquals(VsdmErrorCode.EGK_CERT_INVALID, ex.errorCode());
    }

    private ReadVsdService serviceWithNoCardAccess(CmCardList list) {
        return new ReadVsdService(list, resolverFor(new ScriptedCardReaderPort(ctid, List.of())),
                CardToCardAuthenticator.NONE, 30_000);
    }

    @Test
    void test_FR_002_rejects_hba_without_userid() {
        CmCardList list = new CmCardList();
        list.add(CardObject.builder().cardHandle("egk").ctid(ctid).slotNo(1).type(CardType.EGK).build());
        list.add(CardObject.builder().cardHandle("hba").ctid(ctid).slotNo(2).type(CardType.HBA).build());
        var req = new ReadVsdRequest("egk", "hba", false, false, "m1", "c1", "w1", null);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> serviceWithNoCardAccess(list).read(req));
        assertEquals(VsdmErrorCode.INVALID_REQUEST, ex.errorCode());
    }

    @Test
    void test_FR_002_rejects_missing_context_field() {
        var req = new ReadVsdRequest("egk", "smcb", false, false, "m1", "c1", "  ", null);
        VsdmReadException ex = assertThrows(VsdmReadException.class,
                () -> serviceWithNoCardAccess(cardListWithEgkAndSmcb()).read(req));
        assertEquals(VsdmErrorCode.INVALID_REQUEST, ex.errorCode());
    }

    @Test
    void rejects_unknown_ehc_handle() {
        var req = new ReadVsdRequest("nope", "smcb", false, false, "m1", "c1", "w1", null);
        VsdmReadException ex = assertThrows(VsdmReadException.class,
                () -> serviceWithNoCardAccess(cardListWithEgkAndSmcb()).read(req));
        assertEquals(VsdmErrorCode.INVALID_REQUEST, ex.errorCode());
    }

    @Test
    void rejects_hpc_handle_that_is_not_an_hba_or_smcb() {
        CmCardList list = new CmCardList();
        list.add(CardObject.builder().cardHandle("egk").ctid(ctid).slotNo(1).type(CardType.EGK).build());
        list.add(CardObject.builder().cardHandle("kvk").ctid(ctid).slotNo(2).type(CardType.KVK).build());
        var req = new ReadVsdRequest("egk", "kvk", false, false, "m1", "c1", "w1", null);
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> serviceWithNoCardAccess(list).read(req));
        assertEquals(VsdmErrorCode.INVALID_REQUEST, ex.errorCode());
    }

    @Test
    void test_FR_029_second_concurrent_read_of_same_egk_fails_fast() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var port = new ScriptedCardReaderPort(ctid, mandatoryReadScript());
        port.onBeforeTransmit(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var service = new ReadVsdService(cardListWithEgkAndSmcb(), resolverFor(port),
                CardToCardAuthenticator.NONE, 30_000);

        Thread first = new Thread(() -> service.read(validRequest()));
        first.start();
        entered.await(); // first read has reserved the eGK and is mid-transmit

        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> service.read(validRequest()));
        assertEquals(VsdmErrorCode.CARD_BUSY, ex.errorCode());

        release.countDown();
        first.join();
    }
}
