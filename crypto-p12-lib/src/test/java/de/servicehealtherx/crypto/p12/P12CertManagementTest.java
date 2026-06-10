package de.servicehealtherx.crypto.p12;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(P12CertManagementTest.MgmtTestProfile.class)
class P12CertManagementTest {

    /** Redirects certs-dir to target/ so uploaded files don't pollute src/test/resources. */
    public static class MgmtTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.crypto.p12.certs-dir", "target/test-certs-mgmt");
        }
    }

    @Inject
    P12CertManagement management;

    @Inject
    P12CryptoProvider provider;

    // ── listCertificates ─────────────────────────────────────────────────────

    @Test
    void list_certificates_returns_json_array() {
        String json = management.listCertificates();
        assertNotNull(json);
        assertTrue(json.startsWith("["), "must be JSON array: " + json);
        assertTrue(json.endsWith("]"), "must be JSON array: " + json);
    }

    // ── uploadCertificate ─────────────────────────────────────────────────────

    @Test
    void upload_valid_p12_registers_adapter_and_makes_it_available() throws IOException {
        Path autP12 = Path.of(
            "src/test/resources/certs",
            "80276688311000300107-Zeta",
            "80276688311000300107-Zeta-C_SMCB_AUT_E256_X509",
            "80276688311000300107-Zeta-C_SMCB_AUT_E256_X509.p12");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(autP12),
            "AUT P12 test resource not available");

        byte[] p12Data = Files.readAllBytes(autP12);

        management.uploadCertificate("uploaded-aut", p12Data, "00");

        String json = management.listCertificates();
        assertTrue(json.contains("uploaded-aut"),
            "Uploaded alias must appear in listing: " + json);
    }

    @Test
    void upload_corrupt_p12_registers_adapter_without_throwing() {
        byte[] garbage = "not-a-real-pkcs12".getBytes();

        // Must not throw — corrupt P12 is registered in ERROR state
        assertDoesNotThrow(() -> management.uploadCertificate("corrupt-test", garbage, "whatever"));

        String json = management.listCertificates();
        assertTrue(json.contains("corrupt-test"), "Corrupt upload alias must appear in listing: " + json);
    }

    @Test
    void upload_with_blank_alias_throws_illegal_argument_exception() {
        assertThrows(IllegalArgumentException.class,
            () -> management.uploadCertificate("", new byte[]{1, 2, 3}, "password"));
    }

    @Test
    void upload_with_empty_p12_data_throws_illegal_argument_exception() {
        assertThrows(IllegalArgumentException.class,
            () -> management.uploadCertificate("valid-alias", new byte[0], "password"));
    }

    @Test
    void upload_with_null_password_throws_illegal_argument_exception() {
        assertThrows(IllegalArgumentException.class,
            () -> management.uploadCertificate("valid-alias", new byte[]{1, 2, 3}, null));
    }

    @Test
    void mbean_is_registered_on_platform_mbean_server() throws Exception {
        javax.management.MBeanServer server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
        javax.management.ObjectName name = new javax.management.ObjectName(
            "de.servicehealtherx:module=crypto-p12-lib,name=P12CertManagement");
        assertTrue(server.isRegistered(name), "P12CertManagement MBean must be registered");
    }
}
