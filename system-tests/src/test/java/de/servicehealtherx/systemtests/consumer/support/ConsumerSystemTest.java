package de.servicehealtherx.systemtests.consumer.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for the Basis-Consumer black-box SOAP system tests. As <em>system</em> tests they need
 * a live server: when none is reachable at {@link ConsumerSoapClient#BASE_URL} the whole class skips
 * itself via a JUnit assumption rather than failing the reactor build.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ConsumerSystemTest {

    protected final ConsumerSoapClient client = new ConsumerSoapClient();

    @BeforeAll
    void serverMustBeReachable() {
        assumeTrue(client.serverReachable(),
                "No Basis-Consumer server reachable at " + ConsumerSoapClient.BASE_URL
                        + " — skipping consumer SOAP system tests");
    }
}
