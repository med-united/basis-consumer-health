package de.servicehealtherx.quarkus.sicct.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class CardTerminalManufacturerInfoTest {

    /**
     * Exact value of the CardTerminal Manufacturer DO (content inside tag '46') emitted by
     * the reference terminal (virtual-nfc-card-terminal,
     * SICCTCommandInterpreter#getCardTerminalDataObject).
     */
    private static byte[] referenceDo() {
        return new byte[] {
                // Manufacturer "DESRH"
                0x44, 0x45, 'S', 'R', 'H',
                // SICCT terminal version "0120 "
                0x30, 0x31, 0x32, 0x30, 0x20,
                // Software version "0100 "
                0x30, 0x31, 0x30, 0x30, 0x20,
                // Discretionary Data DO (DO_KT_0001): tag 'D7', length 0x33
                (byte) 0xd7, 0x33,
                0x20, 0x20, '1', 0x20, 0x20, '0', 0x20, 0x20, '0', // VER 1.0.0
                'K', 'T', // PT
                0x20, 0x20, '1', 0x20, 0x20, '5', 0x20, 0x20, '0', // PTV 1.5.0
                'N', 'F', 'C', 'K', 'T', 'S', 'H', ' ', // MODN
                0x20, 0x20, '1', 0x20, 0x20, '0', 0x20, 0x20, '3', // FWV 1.0.3
                0x20, 0x20, '1', 0x20, 0x20, '0', 0x20, 0x20, '0', // HWV 1.0.0
                '0', '0', '0', '1', '7' // FWG
        };
    }

    @Test
    public void parses_all_fields_of_reference_do() {
        CardTerminalManufacturerInfo info = CardTerminalManufacturerInfo.parse(referenceDo());

        assertEquals("DESRH", info.manufacturer());
        assertEquals("0120", info.sicctVersion());
        assertEquals("0100", info.softwareVersion());
        assertEquals("1.0.0", info.ehealthInterfaceVersion());
        assertEquals("KT", info.productType());
        assertEquals("1.5.0", info.productTypeVersion());
        assertEquals("NFCKTSH", info.modelName());
        assertEquals("1.0.3", info.firmwareVersion());
        assertEquals("1.0.0", info.hardwareVersion());
        assertEquals("00017", info.firmwareGroup());
    }

    @Test
    public void parses_fixed_fields_when_discretionary_data_absent() {
        byte[] value = new byte[] {
                0x44, 0x45, 'S', 'R', 'H',
                0x30, 0x31, 0x32, 0x30, 0x20,
                0x30, 0x31, 0x30, 0x30, 0x20
        };
        CardTerminalManufacturerInfo info = CardTerminalManufacturerInfo.parse(value);

        assertEquals("DESRH", info.manufacturer());
        assertEquals("0120", info.sicctVersion());
        assertEquals("0100", info.softwareVersion());
        assertNull(info.ehealthInterfaceVersion());
    }

    @Test
    public void truncated_value_does_not_throw() {
        // Buffer shorter than a full fixed field — every field yields null, no exception.
        CardTerminalManufacturerInfo info = CardTerminalManufacturerInfo.parse(new byte[] { 0x44, 0x45 });
        assertNull(info.manufacturer());
        assertNull(info.softwareVersion());
        assertNull(info.ehealthInterfaceVersion());
    }

    @Test
    public void null_value_yields_empty_info() {
        CardTerminalManufacturerInfo info = CardTerminalManufacturerInfo.parse(null);
        assertNull(info.manufacturer());
        assertNull(info.ehealthInterfaceVersion());
    }
}
