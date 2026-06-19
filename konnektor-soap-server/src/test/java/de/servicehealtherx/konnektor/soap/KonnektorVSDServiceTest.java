package de.servicehealtherx.konnektor.soap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.gematik.ws.conn.connectorcontext.v2.ContextType;
import de.gematik.ws.conn.vsds.vsdservice.v5.ReadVSD;
import de.gematik.ws.conn.vsds.vsdservice.v5.ReadVSDResponse;
import de.gematik.ws.conn.vsds.vsdservice.v5_2.FaultMessage;
import de.servicehealtherx.apdu.vsdm.ReadVsdService;
import de.servicehealtherx.apdu.vsdm.VsdReadResult;
import de.servicehealtherx.apdu.vsdm.VsdStatus;
import de.servicehealtherx.apdu.vsdm.VsdmErrorCode;
import de.servicehealtherx.apdu.vsdm.VsdmReadException;

class KonnektorVSDServiceTest {

    private static final byte[] PD = "PERSONAL".getBytes();
    private static final byte[] VD = "GENERAL".getBytes();

    private KonnektorVSDService serviceWith(ReadVsdService backend) {
        KonnektorVSDService svc = new KonnektorVSDService();
        svc.readVsdService = backend;
        svc.faultFactory = new VsdmFaultFactory();
        return svc;
    }

    private ReadVSD request(boolean onlineCheck) {
        ReadVSD r = new ReadVSD();
        r.setEhcHandle("egk");
        r.setHpcHandle("smcb");
        r.setPerformOnlineCheck(onlineCheck);
        r.setReadOnlineReceipt(false);
        ContextType ctx = new ContextType();
        ctx.setMandantId("m1");
        ctx.setClientSystemId("c1");
        ctx.setWorkplaceId("w1");
        r.setContext(ctx);
        return r;
    }

    @Test
    void test_VSDM_A_2634_maps_result_to_response_without_pruefungsnachweis() throws Exception {
        ReadVsdService backend = mock(ReadVsdService.class);
        when(backend.read(any())).thenReturn(new VsdReadResult(PD, VD, Optional.empty(),
                new VsdStatus("0", OffsetDateTime.of(2012, 1, 31, 8, 47, 13, 0, ZoneOffset.ofHours(1)), "7.3.1")));

        ReadVSDResponse response = serviceWith(backend).readVSD(request(false));

        assertArrayEquals(PD, response.getPersoenlicheVersichertendaten());
        assertArrayEquals(VD, response.getAllgemeineVersicherungsdaten());
        assertNull(response.getGeschuetzteVersichertendaten());
        assertNull(response.getPruefungsnachweis(), "no Pruefungsnachweis (out of scope)");
        assertEquals("0", response.getVSDStatus().getStatus());
        assertEquals("7.3.1", response.getVSDStatus().getVersion());
    }

    @Test
    void test_FR_007_rejection_is_mapped_to_soap_fault() throws Exception {
        ReadVsdService backend = mock(ReadVsdService.class);
        when(backend.read(any())).thenThrow(
                new VsdmReadException(VsdmErrorCode.ONLINE_CHECK_NOT_SUPPORTED, "online check not supported"));

        FaultMessage fault = assertThrows(FaultMessage.class, () -> serviceWith(backend).readVSD(request(true)));
        assertEquals("Fatal", fault.getFaultInfo().getTrace().get(0).getSeverity());
    }
}
