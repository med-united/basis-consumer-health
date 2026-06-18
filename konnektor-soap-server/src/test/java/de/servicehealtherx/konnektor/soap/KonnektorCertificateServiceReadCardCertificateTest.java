package de.servicehealtherx.konnektor.soap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import de.gematik.ws.conn.certificateservice.v6.ReadCardCertificate;
import de.gematik.ws.conn.certificateservice.v6.ReadCardCertificateResponse;
import de.gematik.ws.conn.certificateservicecommon.v2.CertRefEnum;
import de.gematik.ws.conn.certificateservicecommon.v2.X509DataInfoListType;
import de.servicehealtherx.crypto.services.CertificateService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that ReadCardCertificate returns one certificate per requested reference, each labelled
 * with its own ref (BUGS.txt #2 — previously a single C.AUT certificate was returned mislabelled
 * with whichever ref the client happened to list first).
 */
class KonnektorCertificateServiceReadCardCertificateTest {

    @Test
    void returns_one_certificate_per_requested_ref() throws Exception {
        CertificateService certificateService = Mockito.mock(CertificateService.class);
        when(certificateService.readCardCertificate(eq("h1"), eq("C.ENC"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new byte[] {1});
        when(certificateService.readCardCertificate(eq("h1"), eq("C.QES"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new byte[] {2});
        when(certificateService.readCardCertificate(eq("h1"), eq("C.AUT"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new byte[] {3});

        KonnektorCertificateService service = new KonnektorCertificateService();
        service.certificateService = certificateService;

        ReadCardCertificate request = new ReadCardCertificate();
        request.setCardHandle("h1");
        ReadCardCertificate.CertRefList refList = new ReadCardCertificate.CertRefList();
        refList.getCertRef().addAll(List.of(CertRefEnum.C_ENC, CertRefEnum.C_QES, CertRefEnum.C_AUT));
        request.setCertRefList(refList);

        ReadCardCertificateResponse response = service.readCardCertificate(request);

        List<X509DataInfoListType.X509DataInfo> infos = response.getX509DataInfoList().getX509DataInfo();
        assertEquals(3, infos.size(), "one entry per requested certificate reference");
        assertEquals(CertRefEnum.C_ENC, infos.get(0).getCertRef());
        assertArrayEquals(new byte[] {1}, infos.get(0).getX509Data().getX509Certificate());
        assertEquals(CertRefEnum.C_QES, infos.get(1).getCertRef());
        assertArrayEquals(new byte[] {2}, infos.get(1).getX509Data().getX509Certificate());
        assertEquals(CertRefEnum.C_AUT, infos.get(2).getCertRef());
        assertArrayEquals(new byte[] {3}, infos.get(2).getX509Data().getX509Certificate());
    }

    @Test
    void skips_unavailable_refs_but_returns_the_readable_ones() throws Exception {
        CertificateService certificateService = Mockito.mock(CertificateService.class);
        when(certificateService.readCardCertificate(eq("h1"), eq("C.AUT"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new byte[] {3});
        when(certificateService.readCardCertificate(eq("h1"), eq("C.QES"), Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new RuntimeException("C.QES not present on this card"));

        KonnektorCertificateService service = new KonnektorCertificateService();
        service.certificateService = certificateService;

        ReadCardCertificate request = new ReadCardCertificate();
        request.setCardHandle("h1");
        ReadCardCertificate.CertRefList refList = new ReadCardCertificate.CertRefList();
        refList.getCertRef().addAll(List.of(CertRefEnum.C_AUT, CertRefEnum.C_QES));
        request.setCertRefList(refList);

        ReadCardCertificateResponse response = service.readCardCertificate(request);

        List<X509DataInfoListType.X509DataInfo> infos = response.getX509DataInfoList().getX509DataInfo();
        assertEquals(1, infos.size(), "the readable cert is returned; the absent one is skipped");
        assertEquals(CertRefEnum.C_AUT, infos.get(0).getCertRef());
    }
}
