package de.servicehealtherx.quarkus.sicct.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Pure-function tests for the TUC_KON_053 pairing helpers in
 * {@link SicctTerminalManager}. The orchestration itself drives live Netty/TLS
 * connections and is exercised by the IT suite; here only the deterministic
 * fingerprint formatting is verified.
 */
public class SicctTerminalManagerPairingTest {

    @Test
    public void confirmFingerprint_unknownFingerprint_returnsNoPendingPairing() {
        SicctTerminalManager manager = new SicctTerminalManager();
        assertEquals("NO_PENDING_PAIRING", manager.confirmFingerprint("AA:BB:CC"));
    }

    @Test
    public void rejectFingerprint_unknownFingerprint_returnsNoPendingPairing() {
        SicctTerminalManager manager = new SicctTerminalManager();
        assertEquals("NO_PENDING_PAIRING", manager.rejectFingerprint("AA:BB:CC"));
    }

    @Test
    public void sha256Fingerprint_formatsColonSeparatedUpperHex() {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        String fingerprint = SicctTerminalManager.sha256Fingerprint("hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "2C:F2:4D:BA:5F:B0:A3:0E:26:E8:3B:2A:C5:B9:E2:9E:"
                        + "1B:16:1E:5C:1F:A7:42:5E:73:04:33:62:93:8B:98:24",
                fingerprint);
    }
}
