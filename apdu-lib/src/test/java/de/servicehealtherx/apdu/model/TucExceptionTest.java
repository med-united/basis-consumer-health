package de.servicehealtherx.apdu.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TucExceptionTest {

    @Test
    void constructorStoresCodeAndIdentifier() {
        var ex = new TucException(4001, "TUC_KON_001", "test");
        assertEquals(4001, ex.getErrorCode());
        assertEquals("TUC_KON_001", ex.getTucIdentifier());
        assertEquals("test", ex.getMessage());
    }

    @Test
    void constructorWithCauseStoresCause() {
        var cause = new RuntimeException("root");
        var ex = new TucException(4001, "TUC_KON_001", "msg", cause);
        assertSame(cause, ex.getCause());
    }

    @ParameterizedTest
    @ValueSource(ints = {4000, 4095, 0, -1, 9999})
    void invalidErrorCodeThrows(int badCode) {
        assertThrows(IllegalArgumentException.class,
                () -> new TucException(badCode, "TUC_KON_001", "msg"));
    }

    @Test
    void blankIdentifierThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new TucException(4001, "  ", "msg"));
    }

    @Test
    void factoryPinBlocked() {
        var ex = TucException.pinBlocked("TUC_KON_026");
        assertEquals(4063, ex.getErrorCode());
        assertEquals("TUC_KON_026", ex.getTucIdentifier());
    }

    @Test
    void factoryTransportPin() {
        var ex = TucException.transportPin("TUC_KON_026");
        assertEquals(4065, ex.getErrorCode());
    }

    @Test
    void factoryCardReservedByOther() {
        var ex = TucException.cardReservedByOther("TUC_KON_026");
        assertEquals(4093, ex.getErrorCode());
    }

    @Test
    void factoryCardAccessTimeout() {
        var ex = TucException.cardAccessTimeout("TUC_KON_026");
        assertEquals(4094, ex.getErrorCode());
    }

    @Test
    void factoryInvalidPinRef() {
        var ex = TucException.invalidPinRef("TUC_KON_026");
        assertEquals(4072, ex.getErrorCode());
    }

    @Test
    void factoryRemoteKtNotConfigured() {
        var ex = TucException.remoteKtNotConfigured("TUC_KON_036");
        assertEquals(4092, ex.getErrorCode());
    }

    @Test
    void factoryKvkWriteRejected() {
        var ex = TucException.kvkWriteRejected("TUC_KON_200");
        assertEquals(4001, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("KVK"));
    }

    @Test
    void factoryMissingC2CCard() {
        var ex = TucException.missingC2CCard("TUC_KON_005");
        assertEquals(4071, ex.getErrorCode());
    }

    @Test
    void exceptionIsRuntimeException() {
        assertInstanceOf(RuntimeException.class, TucException.pinBlocked("TUC_KON_026"));
    }
}
