package de.servicehealtherx.crypto.p12;

import de.servicehealtherx.crypto.KeyStoreAvailability;
import de.servicehealtherx.crypto.adapter.P12KeyStoreAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class P12CertScannerTest {

    private final P12CertScanner scanner = new P12CertScanner();

    // ── alias derivation ──────────────────────────────────────────────────────

    @Test
    void alias_derived_correctly_from_flat_file() {
        Path root = Path.of("/certs");
        Path p12 = Path.of("/certs/smcb-aut.p12");
        assertEquals("p12/smcb-aut", P12CertScanner.deriveAlias(root, p12));
    }

    @Test
    void alias_derived_correctly_from_nested_path() {
        Path root = Path.of("/certs");
        Path p12 = Path.of("/certs/zeta/smcb-aut.p12");
        assertEquals("p12/zeta/smcb-aut", P12CertScanner.deriveAlias(root, p12));
    }

    @Test
    void alias_normalises_uppercase_to_lowercase() {
        Path root = Path.of("/certs");
        Path p12 = Path.of("/certs/Zeta/C_SMCB_AUT.p12");
        String alias = P12CertScanner.deriveAlias(root, p12);
        assertTrue(alias.equals(alias.toLowerCase()), "alias must be lowercase: " + alias);
        assertEquals("p12/zeta/c_smcb_aut", alias);
    }

    @Test
    void alias_replaces_disallowed_chars_with_hyphen() {
        Path root = Path.of("/certs");
        Path p12 = Path.of("/certs/my.card@v2.p12");
        String alias = P12CertScanner.deriveAlias(root, p12);
        assertFalse(alias.contains("@"), "alias must not contain @");
        assertTrue(alias.startsWith("p12/"));
    }

    @Test
    void alias_prefixed_with_p12() {
        Path root = Path.of("/certs");
        Path p12 = Path.of("/certs/aut.p12");
        assertTrue(P12CertScanner.deriveAlias(root, p12).startsWith("p12/"));
    }

    // ── scan: empty / missing dir ─────────────────────────────────────────────

    @Test
    void empty_certs_dir_returns_empty_list(@TempDir Path tempDir) {
        List<P12KeyStoreAdapter> result = scanner.scan(tempDir.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    void missing_certs_dir_returns_empty_list(@TempDir Path tempDir) throws IOException {
        Path missing = tempDir.resolve("nonexistent");
        List<P12KeyStoreAdapter> result = scanner.scan(missing.toString());
        assertTrue(result.isEmpty());
    }

    // ── scan: password.txt discovery ─────────────────────────────────────────

    @Test
    void missing_password_txt_marks_adapter_as_error(@TempDir Path tempDir) throws IOException {
        // copy a real P12 but don't create password.txt
        Path autDir = Path.of("src/test/resources/certs/80276688311000300107-Zeta/80276688311000300107-Zeta-C_SMCB_AUT_E256_X509");
        Path sourceP12 = autDir.resolve("80276688311000300107-Zeta-C_SMCB_AUT_E256_X509.p12");
        assumeP12Exists(sourceP12);

        Files.copy(sourceP12, tempDir.resolve("test.p12"));
        // deliberately NO password.txt

        List<P12KeyStoreAdapter> adapters = scanner.scan(tempDir.toString());
        assertEquals(1, adapters.size());
        assertEquals(KeyStoreAvailability.ERROR, adapters.get(0).descriptor().getAvailability());
        assertNotNull(adapters.get(0).descriptor().getErrorMessage());
    }

    @Test
    void scan_discovers_three_zeta_p12_files() {
        // Uses the actual test resources already on disk
        List<P12KeyStoreAdapter> adapters = scanner.scan("src/test/resources/certs");
        assertEquals(3, adapters.size(),
            "expected AUT + ENC + OSIG; got " + adapters.stream()
                .map(a -> a.descriptor().alias.value()).toList());
    }

    @Test
    void all_zeta_adapters_are_available_after_scan() {
        List<P12KeyStoreAdapter> adapters = scanner.scan("src/test/resources/certs");
        for (P12KeyStoreAdapter adapter : adapters) {
            assertEquals(KeyStoreAvailability.AVAILABLE, adapter.descriptor().getAvailability(),
                "expected AVAILABLE for " + adapter.descriptor().alias.value()
                    + " but got " + adapter.descriptor().getAvailability()
                    + ": " + adapter.descriptor().getErrorMessage());
        }
    }

    @Test
    void corrupt_p12_marks_adapter_as_error_others_available(@TempDir Path tempDir) throws IOException {
        // create a corrupt p12 with a valid password.txt
        Path subDir = tempDir.resolve("sub");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("bad.p12"), "not-a-real-pkcs12");
        Files.writeString(subDir.resolve("password.txt"), "irrelevant");

        // also add a real P12 so we can verify isolation
        Path autSrc = Path.of("src/test/resources/certs/80276688311000300107-Zeta/80276688311000300107-Zeta-C_SMCB_AUT_E256_X509");
        if (Files.exists(autSrc.resolve("80276688311000300107-Zeta-C_SMCB_AUT_E256_X509.p12"))) {
            Path goodDir = tempDir.resolve("good");
            Files.createDirectories(goodDir);
            Files.copy(autSrc.resolve("80276688311000300107-Zeta-C_SMCB_AUT_E256_X509.p12"), goodDir.resolve("aut.p12"));
            Files.writeString(goodDir.resolve("password.txt"), "00");
        }

        List<P12KeyStoreAdapter> adapters = scanner.scan(tempDir.toString());
        long errorCount = adapters.stream()
            .filter(a -> a.descriptor().getAvailability() == KeyStoreAvailability.ERROR).count();
        long availableCount = adapters.stream()
            .filter(a -> a.descriptor().getAvailability() == KeyStoreAvailability.AVAILABLE).count();

        assertTrue(errorCount >= 1, "corrupt P12 should be in ERROR");
        assertTrue(availableCount >= 1, "good P12 should be AVAILABLE");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assumeP12Exists(Path p12) {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(p12),
            "test P12 not available: " + p12);
    }
}
