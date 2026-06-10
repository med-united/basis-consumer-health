package de.servicehealtherx.crypto.p12;

import de.servicehealtherx.crypto.KeyAlias;
import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.KeyStoreDescriptor;
import de.servicehealtherx.crypto.model.CryptoOperationRequest;
import de.servicehealtherx.crypto.model.CryptoOperationResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class P12CryptoProviderTest {

    @Inject
    P12CryptoProvider provider;

    private String autAlias;

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void findAutAlias() {
        autAlias = provider.listKeyStores().stream()
            .filter(d -> d.alias.value().contains("aut"))
            .filter(d -> d.getAvailability() == KeyStoreAvailability.AVAILABLE)
            .map(d -> d.alias.value())
            .findFirst()
            .orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(autAlias != null,
            "No AVAILABLE AUT P12 adapter found — check test resources");
    }

    // ── sign ─────────────────────────────────────────────────────────────────

    @Test
    void sign_auto_detects_ecdsa_algorithm_for_ec_key() {
        byte[] data = "hello TI".getBytes(StandardCharsets.UTF_8);
        CryptoOperationRequest req = new CryptoOperationRequest(new KeyAlias(autAlias), null, data, "test");

        CryptoOperationResult result = provider.sign(req);

        assertNotNull(result.result);
        assertTrue(result.result.length > 0);
        assertEquals("SHA256withECDSA", result.algorithm,
            "auto-detected algorithm must be SHA256withECDSA for EC key");
    }

    @Test
    void sign_with_explicit_algorithm_overrides_auto_detect() {
        byte[] data = "hello TI".getBytes(StandardCharsets.UTF_8);
        CryptoOperationRequest req = new CryptoOperationRequest(
            new KeyAlias(autAlias), "SHA256withECDSA", data, "test");

        CryptoOperationResult result = provider.sign(req);

        assertNotNull(result.result);
        assertEquals("SHA256withECDSA", result.algorithm);
    }

    @Test
    void sign_with_unknown_alias_throws_illegal_argument_exception() {
        KeyAlias unknown = new KeyAlias("p12/no-such-cert");
        CryptoOperationRequest req = new CryptoOperationRequest(unknown, null,
            "data".getBytes(StandardCharsets.UTF_8), "test");

        assertThrows(IllegalArgumentException.class, () -> provider.sign(req),
            "sign with unknown alias must throw IAE");
    }

    @Test
    void sign_with_wrong_source_type_throws_illegal_argument_exception() {
        KeyAlias pcscAlias = new KeyAlias("pcsc/reader-01");
        CryptoOperationRequest req = new CryptoOperationRequest(pcscAlias, null,
            "data".getBytes(StandardCharsets.UTF_8), "test");

        assertThrows(IllegalArgumentException.class, () -> provider.sign(req),
            "P12CryptoProvider must reject non-P12 aliases");
    }

    // ── encrypt / decrypt ────────────────────────────────────────────────────

    @Test
    void encrypt_throws_unsupported_operation_exception() {
        CryptoOperationRequest req = new CryptoOperationRequest(new KeyAlias(autAlias), null,
            "plaintext".getBytes(StandardCharsets.UTF_8), "test");

        assertThrows(UnsupportedOperationException.class, () -> provider.encrypt(req),
            "encrypt must throw UOE — ECIES is a dedicated feature");
    }

    @Test
    void decrypt_throws_unsupported_operation_exception() {
        CryptoOperationRequest req = new CryptoOperationRequest(new KeyAlias(autAlias), null,
            "ciphertext".getBytes(StandardCharsets.UTF_8), "test");

        assertThrows(UnsupportedOperationException.class, () -> provider.decrypt(req),
            "decrypt must throw UOE — ECIES is a dedicated feature");
    }

    // ── availability ─────────────────────────────────────────────────────────

    @Test
    void list_key_stores_returns_all_zeta_adapters() {
        List<KeyStoreDescriptor> stores = provider.listKeyStores();
        assertFalse(stores.isEmpty(), "listKeyStores must return at least one entry");
        assertTrue(stores.stream().anyMatch(d -> d.alias.value().contains("aut")),
            "AUT adapter must be listed");
    }

    @Test
    void get_availability_returns_available_for_loaded_cert() {
        KeyStoreAvailability avail = provider.getAvailability(new KeyAlias(autAlias));
        assertEquals(KeyStoreAvailability.AVAILABLE, avail);
    }

    @Test
    void get_availability_returns_unavailable_for_unknown_alias() {
        KeyStoreAvailability avail = provider.getAvailability(new KeyAlias("p12/no-such-cert"));
        assertEquals(KeyStoreAvailability.UNAVAILABLE, avail);
    }

    @Test
    void get_availability_returns_unavailable_for_wrong_source_type() {
        KeyStoreAvailability avail = provider.getAvailability(new KeyAlias("pcsc/reader-01"));
        assertEquals(KeyStoreAvailability.UNAVAILABLE, avail,
            "P12CryptoProvider returns UNAVAILABLE for non-P12 aliases");
    }

    @Test
    void get_availabilities_returns_map_with_p12_entries() {
        Map<String, KeyStoreAvailability> map = provider.getAvailabilities();
        assertFalse(map.isEmpty());
        assertTrue(map.keySet().stream().allMatch(k -> k.startsWith("p12/")),
            "all keys must start with p12/");
    }

    // ── verify ───────────────────────────────────────────────────────────────

    @Test
    void verify_validates_signature_produced_by_sign() {
        byte[] data = "round-trip verify".getBytes(StandardCharsets.UTF_8);
        KeyAlias alias = new KeyAlias(autAlias);

        CryptoOperationResult signed = provider.sign(
            new CryptoOperationRequest(alias, null, data, "test"));

        boolean valid = provider.verify(
            new CryptoOperationRequest(alias, signed.algorithm, data, "test"),
            signed.result);

        assertTrue(valid, "verify must return true for a signature produced by sign");
    }

    @Test
    void verify_rejects_tampered_data() {
        byte[] data = "original data".getBytes(StandardCharsets.UTF_8);
        KeyAlias alias = new KeyAlias(autAlias);

        CryptoOperationResult signed = provider.sign(
            new CryptoOperationRequest(alias, null, data, "test"));

        byte[] tampered = "tampered data".getBytes(StandardCharsets.UTF_8);
        boolean valid = provider.verify(
            new CryptoOperationRequest(alias, signed.algorithm, tampered, "test"),
            signed.result);

        assertFalse(valid, "verify must return false for tampered data");
    }
}
