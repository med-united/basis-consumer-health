package de.servicehealtherx.apdu.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthStateTest {

    private AuthState state;

    @BeforeEach
    void setUp() {
        state = new AuthState();
    }

    @Test
    void initiallyNoPinVerified() {
        assertFalse(state.isPinVerified(PinRef.PIN_CH));
    }

    @Test
    void initiallyNoKeyAuthenticated() {
        assertFalse(state.isKeyAuthenticated(KeyRef.C_AUT));
    }

    @Test
    void markPinVerifiedReturnsTrue() {
        state.markPinVerified(PinRef.PIN_CH);
        assertTrue(state.isPinVerified(PinRef.PIN_CH));
    }

    @Test
    void markPinVerifiedDoesNotAffectOtherRefs() {
        state.markPinVerified(PinRef.PIN_CH);
        assertFalse(state.isPinVerified(PinRef.PIN_QES));
    }

    @Test
    void markKeyAuthenticatedReturnsTrue() {
        state.markKeyAuthenticated(KeyRef.C_AUT);
        assertTrue(state.isKeyAuthenticated(KeyRef.C_AUT));
    }

    @Test
    void markKeyDoesNotAffectOtherKeys() {
        state.markKeyAuthenticated(KeyRef.C_AUT);
        assertFalse(state.isKeyAuthenticated(KeyRef.C_ENC));
    }

    @Test
    void clearResetsAllState() {
        state.markPinVerified(PinRef.PIN_CH);
        state.markKeyAuthenticated(KeyRef.C_AUT);

        state.clear();

        assertFalse(state.isPinVerified(PinRef.PIN_CH));
        assertFalse(state.isKeyAuthenticated(KeyRef.C_AUT));
    }

    @Test
    void getPinStatusDefaultsToOk() {
        assertEquals(PinStatus.OK, state.getPinStatus(PinRef.PIN_CH));
    }

    @Test
    void markPinStatusPreservesCustomStatus() {
        state.markPinStatus(PinRef.PIN_CH, PinStatus.BLOCKED);
        assertEquals(PinStatus.BLOCKED, state.getPinStatus(PinRef.PIN_CH));
        assertFalse(state.isPinVerified(PinRef.PIN_CH));
    }

    @Test
    void markPinVerifiedSetsPinStatusToVerified() {
        state.markPinVerified(PinRef.PIN_QES);
        assertEquals(PinStatus.VERIFIED, state.getPinStatus(PinRef.PIN_QES));
        assertTrue(state.isPinVerified(PinRef.PIN_QES));
    }
}
