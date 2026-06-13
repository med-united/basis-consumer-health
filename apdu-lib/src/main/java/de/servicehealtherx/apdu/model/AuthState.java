package de.servicehealtherx.apdu.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AuthState {

    private final Map<PinRef, PinStatus> verifiedPins = new EnumMap<>(PinRef.class);
    private final Set<KeyRef> authedKeys = EnumSet.noneOf(KeyRef.class);

    public boolean isPinVerified(PinRef ref) {
        return verifiedPins.get(ref) == PinStatus.VERIFIED;
    }

    public PinStatus getPinStatus(PinRef ref) {
        return verifiedPins.getOrDefault(ref, PinStatus.OK);
    }

    public boolean isKeyAuthenticated(KeyRef ref) {
        return authedKeys.contains(ref);
    }

    public void markPinVerified(PinRef ref) {
        verifiedPins.put(ref, PinStatus.VERIFIED);
    }

    public void markPinStatus(PinRef ref, PinStatus status) {
        verifiedPins.put(ref, status);
    }

    public void markKeyAuthenticated(KeyRef ref) {
        authedKeys.add(ref);
    }

    public void clear() {
        verifiedPins.clear();
        authedKeys.clear();
    }
}
