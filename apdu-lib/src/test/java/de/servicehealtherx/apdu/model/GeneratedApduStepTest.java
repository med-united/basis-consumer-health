package de.servicehealtherx.apdu.model;

import org.junit.jupiter.api.Test;

import javax.smartcardio.CommandAPDU;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedApduStepTest {

    private static CommandAPDU sampleCommand() {
        return new CommandAPDU(0x00, 0xA4, 0x04, 0x00);
    }

    @Test
    void constructorStoresAllFields() {
        var cmd = sampleCommand();
        var ess = ExpectedStatusSet.successOnly();
        var step = new GeneratedApduStep(cmd, ess, "SELECT MF");

        assertSame(cmd, step.command());
        assertSame(ess, step.expectedStatuses());
        assertEquals("SELECT MF", step.semanticLabel());
    }

    @Test
    void nullCommandThrows() {
        assertThrows(NullPointerException.class,
                () -> new GeneratedApduStep(null, ExpectedStatusSet.successOnly(), "label"));
    }

    @Test
    void nullExpectedStatusesThrows() {
        assertThrows(NullPointerException.class,
                () -> new GeneratedApduStep(sampleCommand(), null, "label"));
    }

    @Test
    void nullSemanticLabelThrows() {
        assertThrows(NullPointerException.class,
                () -> new GeneratedApduStep(sampleCommand(), ExpectedStatusSet.successOnly(), null));
    }

    @Test
    void recordEquality() {
        var cmd = sampleCommand();
        var ess = ExpectedStatusSet.successOnly();
        var a = new GeneratedApduStep(cmd, ess, "SELECT");
        var b = new GeneratedApduStep(cmd, ess, "SELECT");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
