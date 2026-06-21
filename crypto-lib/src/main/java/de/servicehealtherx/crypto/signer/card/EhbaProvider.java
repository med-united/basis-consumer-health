package de.servicehealtherx.crypto.signer.card;

import java.security.Provider;

/**
 * JCE {@link Provider} that routes {@code SHA256withECDSA} signing onto an inserted smart card via
 * {@link EhbaCardEcdsaSignatureSpi}. {@code CadesSignature}, {@code PadesSignature} and
 * {@code XadesSignature} request it by name ({@code JcaContentSignerBuilder.setProvider("EHBA")} /
 * the XMLDSig {@code SignatureProvider} property), so it must be installed in the JVM's provider
 * list — see {@code EhbaProviderRegistrar}.
 *
 * <p>It is registered <em>appended</em> (not at position 1): every consumer names it explicitly, so
 * a plain {@code Signature.getInstance("SHA256withECDSA")} elsewhere must keep resolving to the
 * software providers (SunEC / BC), never to the card.
 */
public final class EhbaProvider extends Provider {

    public static final String NAME = "EHBA";

    public EhbaProvider() {
        super(NAME, "1.0", "ServiceHealthRX eHBA/SMC-B card-backed ECDSA signing");
        put("Signature.SHA256withECDSA", EhbaCardEcdsaSignatureSpi.class.getName());
    }
}
