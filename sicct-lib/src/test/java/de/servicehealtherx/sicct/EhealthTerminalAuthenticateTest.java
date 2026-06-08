package de.servicehealtherx.sicct;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class EhealthTerminalAuthenticateTest {

    // 6B0000017F000000004881AA000142D410DB5DE13C49DAB037BA122DD466102435502E4B543A4134343233423A454634334541204D4954204B4F4E3A636F6E6E2D61742D72752050414952454E204F4B3F00
    @Test
    public void testBuildCreate() throws Exception {
        byte[] result = EhealthTerminalAuthenticate.buildCreate(new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16
        }, "KT:A4423B:EF43EA MIT KON:conn-at-ru PAIREN OK?");

        assertEquals(
                "81AA000142D41001020304050607080910111213141516502E4B543A4134343233423A454634334541204D4954204B4F4E3A636F6E6E2D61742D72752050414952454E204F4B3F00",
                new String(bytesToHex(result)));

    }

    private char[] bytesToHex(byte[] result) {
        char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[result.length * 2];
        for (int j = 0; j < result.length; j++) {
            int v = result[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return hexChars;
    }
}
