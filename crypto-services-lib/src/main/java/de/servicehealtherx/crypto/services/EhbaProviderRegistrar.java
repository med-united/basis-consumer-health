package de.servicehealtherx.crypto.services;

import de.servicehealtherx.crypto.signer.card.EhbaProvider;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jboss.logging.Logger;

import java.security.Security;

/**
 * Installs the JCE providers the card-backed advanced-signature path depends on, once, at startup:
 *
 * <ul>
 *   <li>{@code BC} (BouncyCastle) — brainpool curve support and the CMS digest provider used by
 *       {@code CadesSignature}/{@code PadesSignature}; inserted at position 1 if not already present
 *       (the P12 provider does the same lazily, this guarantees it regardless of bean init order).</li>
 *   <li>{@code EHBA} — {@link EhbaProvider}, routing {@code SHA256withECDSA} onto the inserted card.
 *       Appended (never at position 1) so it is used only when requested by name.</li>
 * </ul>
 */
@ApplicationScoped
public class EhbaProviderRegistrar {

    private static final Logger LOG = Logger.getLogger(EhbaProviderRegistrar.class);

    void onStart(@Observes StartupEvent event) {
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            LOG.info("[EhbaProviderRegistrar] registered BouncyCastle JCE provider");
        }
        if (Security.getProvider(EhbaProvider.NAME) == null) {
            Security.addProvider(new EhbaProvider());
            LOG.infof("[EhbaProviderRegistrar] registered %s card-signing JCE provider", EhbaProvider.NAME);
        }
    }
}
