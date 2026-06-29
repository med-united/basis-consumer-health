package de.servicehealtherx.quarkus.mtls;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Application entry point. It generates the mTLS PKI ({@link MtlsBootstrap#ensureMaterials()})
 * <em>before</em> handing control to Quarkus, guaranteeing the key store and trust store exist on
 * disk by the time the HTTPS connector binds.
 *
 * <p>Declaring an explicit {@link QuarkusMain} replaces Quarkus' synthesised main; it has no effect
 * on {@code @QuarkusTest} runs (which start the app through the test harness, not this method), so
 * the test suites keep booting over plain HTTP. Production — the packaged runner jar runs under the
 * {@code prod} profile — gets the HTTPS/mTLS connector wired up in {@code application.properties}.
 */
@QuarkusMain
public class MtlsMain {

    public static void main(String... args) {
        MtlsBootstrap.ensureMaterials();
        Quarkus.run(args);
    }
}
