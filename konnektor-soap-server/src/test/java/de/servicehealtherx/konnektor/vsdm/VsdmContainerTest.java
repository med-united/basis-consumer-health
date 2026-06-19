package de.servicehealtherx.konnektor.vsdm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

class VsdmContainerTest {

    private static byte[] gzip(String content) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private static String gunzip(byte[] gz) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] lengthPrefixed(byte[] gz) {
        byte[] out = new byte[gz.length + 2];
        out[0] = (byte) (gz.length >>> 8);
        out[1] = (byte) gz.length;
        System.arraycopy(gz, 0, out, 2, gz.length);
        return out;
    }

    @Test
    void personalData_strips_two_byte_length_prefix() {
        byte[] payload = gzip("PD-XML");
        byte[] result = VsdmContainer.personalData(lengthPrefixed(payload));
        assertArrayEquals(payload, result);
        assertEquals("PD-XML", gunzip(result));
    }

    @Test
    void personalData_honours_declared_length_over_trailing_card_padding() {
        byte[] payload = gzip("PD-XML");
        byte[] framed = lengthPrefixed(payload);
        // simulate the card returning the whole allocated EF with trailing 0x00 padding
        byte[] padded = new byte[framed.length + 16];
        System.arraycopy(framed, 0, padded, 0, framed.length);
        byte[] result = VsdmContainer.personalData(padded);
        assertArrayEquals(payload, result, "padding past the declared length must be dropped");
    }

    @Test
    void generalData_slices_avd_by_offsets_and_ignores_transitional_gvd() {
        byte[] avd = gzip("AVD-XML");
        byte[] gvdCopy = gzip("GVD-TRANSITIONAL");
        int start = 8;
        int endAvd = start + avd.length;
        int endGvd = endAvd + gvdCopy.length;
        byte[] efVd = new byte[endGvd];
        efVd[0] = (byte) (start >>> 8);   efVd[1] = (byte) start;
        efVd[2] = (byte) (endAvd >>> 8);  efVd[3] = (byte) endAvd;
        efVd[4] = (byte) (endAvd >>> 8);  efVd[5] = (byte) endAvd;
        efVd[6] = (byte) (endGvd >>> 8);  efVd[7] = (byte) endGvd;
        System.arraycopy(avd, 0, efVd, start, avd.length);
        System.arraycopy(gvdCopy, 0, efVd, endAvd, gvdCopy.length);

        byte[] result = VsdmContainer.generalData(efVd);
        assertArrayEquals(avd, result);
        assertEquals("AVD-XML", gunzip(result), "transitional GVD copy must not leak (VSDM-A_2784)");
    }

    @Test
    void already_gzip_payload_passes_through_unchanged() {
        byte[] payload = gzip("bare");
        assertArrayEquals(payload, VsdmContainer.personalData(payload));
        assertArrayEquals(payload, VsdmContainer.generalData(payload));
    }

    @Test
    void non_gzip_after_deframing_fails_loudly() {
        byte[] garbage = {0x00, 0x02, 0x41, 0x42}; // length=2, payload "AB" — not gzip
        VsdmReadException ex = assertThrows(VsdmReadException.class, () -> VsdmContainer.personalData(garbage));
        assertEquals(VsdmErrorCode.VSD_READ_FAILED, ex.errorCode());
    }

    /**
     * Regression for the reported defect: the raw EF.PD read off a real eGK (2-byte length prefix
     * {@code 0x01 0x85} + gzip) must become a stream that gunzips to the VSDM XML, not the raw blob
     * that triggered {@code java.util.zip.ZipException: Not in GZIP format}.
     */
    @Test
    void personalData_makes_real_egk_efpd_gunzippable() {
        byte[] efPd = Base64.getDecoder().decode(
                "AYUfiwgAAAAAAAD/jVLLTsMwEPyVyPfGCSLQoo0Rojwq9YFaUbhVJlmaqImDvE6Bfik/wF9wYMOjNIgDFzsznp0"
                + "dewPHT2XhrdFSXplYhH4gPDRJleZmGYvBbNLpdqNeJ4yER06bVBeVwVg8I4ljBdeniysurdAUeZLhvLHh3TpkJS"
                + "+3o6F32h8t5mfT2WAyjkXk7zUduKehWGTOPRxJ+Uj+Ekvt8pWforzXck1p2SxyzXqhYMfXtpBZDPrqNgyDg739I"
                + "DgA+fsMPuIZBRd4V1tHnKouVdjrRkEUsr5Fw7yyRpeohvVKE5t9QRjrJPv46r+WJdrOiyWHKcgtz/aUZAUmmVOj"
                + "xnWLuLZEQ5uatNuoc4s5h7NN6Q4NM2c1EZqT1CLvHLsiV2DuNjorVNgNokOQLQ4m1qkpM1WyAtkAGPJ4FNxUmSE"
                + "WFZpngJYnybFB/kWD/Cz56q6Gb6Z5jiVajykf5PcBXOqaTN3cXYUgd9BW8xNdfj+5bI9N/uNnUe/A0GPvjwIAAA==");

        byte[] payload = VsdmContainer.personalData(efPd);
        assertEquals(0x1f, payload[0] & 0xFF);
        assertEquals(0x8b, payload[1] & 0xFF);
        String xml = gunzip(payload);
        assertTrue(xml.contains("UC_PersoenlicheVersichertendatenXML"), "must gunzip to the VSDM PD XML");
        assertTrue(xml.contains("X110624006"), "must contain the insured-person id");
    }
}
