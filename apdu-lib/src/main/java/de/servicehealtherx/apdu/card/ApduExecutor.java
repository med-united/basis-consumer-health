package de.servicehealtherx.apdu.card;

import java.util.ArrayList;
import java.util.List;

import javax.smartcardio.ResponseAPDU;

import de.servicehealtherx.apdu.card.transport.CardReaderPort;
import de.servicehealtherx.apdu.card.transport.CardTransportException;
import de.servicehealtherx.apdu.model.GeneratedApduStep;
import de.servicehealtherx.apdu.model.TucGenerationResult;

/**
 * Runs a {@link TucGenerationResult}'s ordered {@link GeneratedApduStep}s against a
 * {@link CardReaderPort} and validates each step's status word.
 *
 * <p>The {@code apdu-lib} TUC classes are pure step <em>generators</em>; they do not transmit. This
 * executor is the single, tested place that turns generated steps into transmitted APDUs and checks
 * the {@code ExpectedStatusSet} of each — the foundational building block every multi-step VSDM read
 * orchestrates (contracts/apdu-executor.md).
 *
 * <p>Single responsibility: transmit + verify status words. It knows nothing about VSDM, C2C or
 * files. It never logs response bodies (which can carry card material or PII) — only the semantic
 * label and the status word.
 */
public final class ApduExecutor {

    private final CardReaderPort port;

    public ApduExecutor(CardReaderPort port) {
        this.port = port;
    }

    /**
     * Transmit each step in order to the card in {@code slotNo}, asserting the response status word
     * is accepted by the step's {@link de.servicehealtherx.apdu.model.ExpectedStatusSet}.
     *
     * @return the per-step responses, in order (so callers can read data fields, challenges, etc.)
     * @throws ApduExecutionException on an unexpected status word
     * @throws CardTransportException if transmission fails
     */
    public List<ResponseAPDU> execute(TucGenerationResult result, int slotNo) throws CardTransportException {
        List<ResponseAPDU> responses = new ArrayList<>(result.steps().size());
        for (GeneratedApduStep step : result.steps()) {
            ResponseAPDU response = port.transmit(slotNo, step.command());
            int sw = response.getSW();
            if (!step.expectedStatuses().accepts(sw)) {
                throw new ApduExecutionException(step.semanticLabel(), sw);
            }
            responses.add(response);
        }
        return responses;
    }
}
