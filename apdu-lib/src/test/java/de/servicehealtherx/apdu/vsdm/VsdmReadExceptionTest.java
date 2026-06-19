package de.servicehealtherx.apdu.vsdm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class VsdmReadExceptionTest {

    @Test
    void carries_code_and_detail() {
        VsdmReadException e = new VsdmReadException(VsdmErrorCode.VSD_READ_FAILED, "EF.PD");
        assertEquals(VsdmErrorCode.VSD_READ_FAILED, e.errorCode());
        assertEquals("EF.PD", e.detail());
    }

    @Test
    void preserves_cause() {
        Throwable cause = new IllegalStateException("boom");
        VsdmReadException e = new VsdmReadException(VsdmErrorCode.CARD_BUSY, "busy", cause);
        assertEquals(VsdmErrorCode.CARD_BUSY, e.errorCode());
        assertSame(cause, e.getCause());
    }
}
