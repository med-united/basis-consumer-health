package de.servicehealtherx.crypto.ecies.jce;

import java.security.Provider;

/**
 * JCE {@link Provider} that registers the {@code "ELC"} {@link javax.crypto.Cipher} for
 * gemSpec_COS ELC transport-key decryption.
 *
 * <p>Used explicitly via {@code Cipher.getInstance("ELC", new ElcSecurityProvider())} rather than
 * installed globally, so it has no side effects on other modules.
 */
public final class ElcSecurityProvider extends Provider {

    public static final String NAME = "ServiceHealthRX-ELC";

    public ElcSecurityProvider() {
        super(NAME, "1.0", "gematik TI-ECIES (ELC) transport-key decryption");
        put("Cipher.ELC", ElcCipherSpi.class.getName());
    }
}
