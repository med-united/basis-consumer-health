package de.servicehealtherx.apdu.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TucGenerationResult(
        List<GeneratedApduStep> steps,
        Map<String, Object> semanticOutputHints
) {

    public TucGenerationResult {
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(semanticOutputHints, "semanticOutputHints");
        steps = List.copyOf(steps);
        semanticOutputHints = Map.copyOf(semanticOutputHints);
    }

    public static TucGenerationResult of(List<GeneratedApduStep> steps) {
        return new TucGenerationResult(steps, Map.of());
    }

    public static TucGenerationResult of(List<GeneratedApduStep> steps, Map<String, Object> hints) {
        return new TucGenerationResult(steps, hints);
    }
}
