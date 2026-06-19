package de.servicehealtherx.konnektor.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import de.gematik.ws.conn.vsds.vsdservice.v5_2.FaultMessage;
import de.gematik.ws.tel.error.v2.Error;
import de.servicehealtherx.konnektor.vsdm.VsdmErrorCode;
import de.servicehealtherx.konnektor.vsdm.VsdmReadException;

class VsdmFaultFactoryTest {

    private final VsdmFaultFactory factory = new VsdmFaultFactory();

    static Stream<Arguments> vsdmCodes() {
        return Stream.of(
                Arguments.of(VsdmErrorCode.VSD_INCONSISTENT),
                Arguments.of(VsdmErrorCode.VSD_READ_FAILED),
                Arguments.of(VsdmErrorCode.SMB_NOT_ENABLED),
                Arguments.of(VsdmErrorCode.HBA_NOT_ENABLED),
                Arguments.of(VsdmErrorCode.EGK_CERT_REVOKED),
                Arguments.of(VsdmErrorCode.EGK_CERT_INVALID),
                Arguments.of(VsdmErrorCode.HCA_BLOCKED));
    }

    @ParameterizedTest
    @MethodSource("vsdmCodes")
    void test_VSDM_A_2682_maps_each_code_to_a_fatal_single_trace_fault(int code) {
        FaultMessage fault = factory.fault(new VsdmReadException(code, "detail"));
        Error error = fault.getFaultInfo();
        assertEquals(1, error.getTrace().size(), "exactly one Trace per fault");
        Error.Trace trace = error.getTrace().get(0);
        assertEquals("Fatal", trace.getSeverity());
        assertEquals(code, trace.getCode().intValue());
        assertFalse(trace.getErrorText().toLowerCase().contains("exception"), "no stack trace leaked");
    }

    @Test
    void rejection_sentinels_produce_a_fatal_fault_without_negative_code() {
        FaultMessage fault = factory.fault(
                new VsdmReadException(VsdmErrorCode.ONLINE_CHECK_NOT_SUPPORTED, "online not supported"));
        Error.Trace trace = fault.getFaultInfo().getTrace().get(0);
        assertEquals("Fatal", trace.getSeverity());
        assertEquals(4001, trace.getCode().intValue());
    }
}
