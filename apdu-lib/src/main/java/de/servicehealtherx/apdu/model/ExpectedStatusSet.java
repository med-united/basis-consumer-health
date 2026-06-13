package de.servicehealtherx.apdu.model;

import java.util.Set;

public record ExpectedStatusSet(Set<Integer> acceptedSw, boolean allowWarnings) {

    public static ExpectedStatusSet of(int... swValues) {
        var set = new java.util.HashSet<Integer>();
        for (int sw : swValues) {
            set.add(sw);
        }
        return new ExpectedStatusSet(Set.copyOf(set), false);
    }

    public static ExpectedStatusSet successOnly() {
        return new ExpectedStatusSet(Set.of(0x9000), false);
    }

    public boolean accepts(int sw) {
        return acceptedSw.contains(sw);
    }
}
