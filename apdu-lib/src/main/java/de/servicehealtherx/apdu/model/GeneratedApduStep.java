package de.servicehealtherx.apdu.model;

import javax.smartcardio.CommandAPDU;
import java.util.Objects;

public record GeneratedApduStep(
        CommandAPDU command,
        ExpectedStatusSet expectedStatuses,
        String semanticLabel
) {

    public GeneratedApduStep {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(expectedStatuses, "expectedStatuses");
        Objects.requireNonNull(semanticLabel, "semanticLabel");
    }
}
