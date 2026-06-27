package de.servicehealtherx.systemtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * End-to-end SICCT pairing + card-discovery system test that, unlike the other black-box suites,
 * <b>starts both systems itself</b>:
 *
 * <ol>
 *   <li>the <b>eHealth-KT</b> terminal simulator (the {@code de.servicehealtherx.ehealthkt:app}
 *       git submodule) as a child JVM with a remote JMX connector;</li>
 *   <li>the <b>Basis-Consumer</b> Konnektor (the {@code quarkus-server} runner jar) as a child JVM
 *       with a remote JMX connector.</li>
 * </ol>
 *
 * It then connects over JMX to <b>both</b> systems and drives the two typical operator use cases:
 *
 * <ul>
 *   <li><b>Pair</b> — discover + connect the terminal on the Konnektor (TUC_KON_050), start
 *       TUC_KON_053 pairing ({@code requestPairTerminal} → fingerprint), confirm the
 *       {@code EHEALTH TERMINAL AUTHENTICATE (CREATE)} prompt on the <i>terminal</i> side over its
 *       JMX ({@code confirmPairing}) while completing it on the <i>Konnektor</i> side
 *       ({@code confirmFingerprint}), and assert the terminal reaches {@code AKTIV} with one active
 *       session and one pairing block.</li>
 *   <li><b>Card handles</b> — once the working session is established, the Konnektor runs SICCT card
 *       discovery; {@code EventService.GetCards} must then return at least one card with a
 *       {@code CardHandle} and a {@code SlotId &ge; 1} (the latter guards the slot-numbering fix).</li>
 * </ul>
 *
 * <p>The test is <b>hardware-gated</b>: the eHealth-KT binds the host's PC/SC readers for its
 * gSMC-KT TLS identity and the card slots, so the whole test {@code assumeTrue}-skips when the
 * terminal jar, the Konnektor runner jar, the readers, or the UDP service-discovery answer are not
 * available — exactly like the other system tests skip without a live server.
 *
 * <p>Configuration via system properties (all optional, sensible defaults):
 * {@code systemtest.terminal.port} (4742), {@code systemtest.konnektor.http.port} (18080),
 * {@code systemtest.konnektor.jmx.port} (13390), {@code systemtest.terminal.jmx.port} (13391),
 * {@code systemtest.konnektor.runner.jar} (auto-located under {@code ../quarkus-server/target}).
 */
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SicctTerminalPairingSystemTest {

    // ── ObjectNames of the MBeans we drive on each side ──────────────────────────────────────────
    private static final String KONN_CONN_MBEAN =
            "de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalConnectionManagement";
    private static final String KONN_DISCOVERY_MBEAN =
            "de.servicehealtherx:module=quarkus-sicct-extension,name=SicctTerminalDiscoveryManagement";
    private static final String KT_MBEAN = "de.servicehealtherx.ehealthkt:type=Management,name=eHealth-KT";

    private static final String EHEALTH_KT_MAIN = "de.servicehealtherx.ehealthkt.app.EhealthKtApplication";

    // ── gematik connector XML namespaces for GetCards (mirrors the other SOAP suites) ─────────────
    private static final String NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String NS_EVENT = "http://ws.gematik.de/conn/EventService/v7.2";
    private static final String NS_CTX = "http://ws.gematik.de/conn/ConnectorContext/v2.0";
    private static final String NS_CCOMMON = "http://ws.gematik.de/conn/ConnectorCommon/v5.0";

    // ── ports / endpoints (overridable) ──────────────────────────────────────────────────────────
    private static final int TERMINAL_PORT = intProp("systemtest.terminal.port", 4742);
    private static final int KONN_HTTP_PORT = intProp("systemtest.konnektor.http.port", 18080);
    private static final int KONN_JMX_PORT = intProp("systemtest.konnektor.jmx.port", 13390);
    private static final int TERMINAL_JMX_PORT = intProp("systemtest.terminal.jmx.port", 13391);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Process terminalProc;
    private Process konnektorProc;
    private JMXConnector konnJmx;
    private JMXConnector ktJmx;
    private MBeanServerConnection konn; // Konnektor platform MBean server
    private MBeanServerConnection kt;   // eHealth-KT platform MBean server
    private Path tempDir;

    /** CTID the Konnektor assigned to the discovered terminal; set in the pairing test. */
    private String ctid;

    @BeforeAll
    void startBothSystems() throws Exception {
        Path runnerJar = locateKonnektorRunnerJar();
        assumeTrue(runnerJar != null,
                "quarkus-server runner jar not found (build it with `mvn -pl quarkus-server -am install`) — skipping");

        tempDir = Files.createTempDirectory("sicct-systest-");

        // 1) eHealth-KT terminal (owns the PC/SC readers) — headless, JMX management on, fresh store.
        terminalProc = startTerminal();
        boolean terminalUp = waitFor(() -> portOpen(TERMINAL_PORT) && terminalProc.isAlive(), Duration.ofSeconds(60));
        if (!terminalUp || !terminalProc.isAlive()) {
            // Most common cause off a card-equipped host: no gSMC-KT / PC-SC readers connected.
            destroyQuietly(terminalProc);
            assumeTrue(false, "eHealth-KT terminal did not come up on port " + TERMINAL_PORT
                    + " (no gSMC-KT / PC-SC readers, or TSL unavailable?) — skipping");
        }

        // 2) Basis-Consumer (Konnektor) — prod runner jar forced onto the dev profile (OIDC off).
        konnektorProc = startKonnektor(runnerJar);
        boolean konnektorUp = waitFor(
                () -> httpStatus("http://localhost:" + KONN_HTTP_PORT + "/q/health") == 200 && konnektorProc.isAlive(),
                Duration.ofSeconds(150));
        if (!konnektorUp || !konnektorProc.isAlive()) {
            destroyQuietly(konnektorProc);
            destroyQuietly(terminalProc);
            assumeTrue(false, "Basis-Consumer (quarkus-server) did not become healthy on :" + KONN_HTTP_PORT
                    + " — skipping");
        }

        // 3) JMX to both systems.
        konnJmx = jmxConnect(KONN_JMX_PORT);
        konn = konnJmx.getMBeanServerConnection();
        ktJmx = jmxConnect(TERMINAL_JMX_PORT);
        kt = ktJmx.getMBeanServerConnection();
    }

    @AfterAll
    void stopBothSystems() {
        closeQuietly(konnJmx);
        closeQuietly(ktJmx);
        destroyQuietly(konnektorProc);
        destroyQuietly(terminalProc);
        if (System.getProperty("systemtest.keep.logs") != null) {
            System.out.println("[systest] keeping logs in " + tempDir);
            return;
        }
        deleteRecursively(tempDir);
    }

    // ── Use case 1: pair the terminal, driving JMX on both the Konnektor and the terminal ─────────
    @Test
    @Order(1)
    void pairsTerminalDrivingJmxOnBothSystems() throws Exception {
        ObjectName kt = new ObjectName(KT_MBEAN);
        ObjectName konnConn = new ObjectName(KONN_CONN_MBEAN);
        ObjectName konnDisc = new ObjectName(KONN_DISCOVERY_MBEAN);

        // Start from a clean terminal pairing store so VALIDATE later matches the single new block.
        this.kt.invoke(kt, "clearPairingBlocks", new Object[0], new String[0]);

        // Konnektor side: ask it to (re)discover, then resolve the terminal's CTID.
        this.konn.invoke(konnDisc, "triggerDiscovery", new Object[0], new String[0]);
        ctid = waitForValue(this::discoverCtid, Duration.ofSeconds(30));
        assumeTrue(ctid != null,
                "Konnektor did not discover the terminal via SICCT UDP service discovery — skipping");

        // TUC_KON_050: bring up the working session.
        invokeStr(konn, konnConn, "connect", ctid);

        // TUC_KON_053: request pairing → fingerprint to confirm.
        String reqJson = invokeStr(konn, konnConn, "requestPairTerminal", ctid);
        String fingerprint = jsonField(reqJson, "fingerprint");
        assertNotNull(fingerprint, "requestPairTerminal returned no fingerprint: " + reqJson);

        // Complete the CREATE confirmation on BOTH sides concurrently: the Konnektor's
        // confirmFingerprint blocks until the terminal answers the prompt, so confirm the terminal
        // prompt (over its JMX) from another thread.
        CompletableFuture<String> konnektorConfirm = CompletableFuture.supplyAsync(() -> {
            try {
                return invokeStr(this.konn, konnConn, "confirmFingerprint", fingerprint);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        boolean prompted = waitFor(() -> getBool(this.kt, kt, "PairingPending"), Duration.ofSeconds(30));
        assertTrue(prompted, "terminal never raised a pairing confirmation prompt");
        this.kt.invoke(kt, "confirmPairing", new Object[0], new String[0]);

        String pairResult = konnektorConfirm.get(60, TimeUnit.SECONDS);
        assertTrue(pairResult.contains("PAIRED"), "pairing did not succeed: " + pairResult);

        // Konnektor reaches AKTIV …
        boolean aktiv = waitFor(() -> {
            try {
                return invokeStr(this.konn, konnConn, "getTerminalStatus", ctid).contains("\"correlation\":\"AKTIV\"");
            } catch (Exception e) {
                return false;
            }
        }, Duration.ofSeconds(30));
        assertTrue(aktiv, "Konnektor terminal did not reach correlation=AKTIV");

        // … and the terminal sees an active working session and one stored pairing block.
        // The Konnektor flips to AKTIV the instant pairing completes, but it then performs an
        // administrative disconnect and re-establishes the TUC_KON_050 working session (a fresh
        // CLIENT_WITH_PAIRING connection + VALIDATE) a few hundred ms later. Only that reconnected
        // session registers in the terminal's SicctSessionRegistry, so poll for it instead of
        // racing the reconnect window (where ActiveSessions is briefly 0).
        boolean sessionUp = waitFor(() -> {
            try {
                return getInt(this.kt, kt, "ActiveSessions") >= 1;
            } catch (Exception e) {
                return false;
            }
        }, Duration.ofSeconds(30));
        assertTrue(sessionUp, "terminal reports no active session");
        assertEquals(1, getInt(this.kt, kt, "PairingBlocks"), "terminal should hold exactly one pairing block");
    }

    // ── Use case 2: the paired terminal's inserted cards become card handles ──────────────────────
    @Test
    @Order(2)
    void exposesCardHandlesForInsertedCardsAfterPairing() throws Exception {
        assumeTrue(ctid != null, "pairing did not run (terminal not discovered) — skipping");

        // Card discovery runs asynchronously once the working session VALIDATEs; poll GetCards.
        String body = waitForValue(() -> {
            HttpResponse<String> resp = getCards();
            return (resp != null && resp.statusCode() == 200 && resp.body().contains("<CardHandle>"))
                    ? resp.body() : null;
        }, Duration.ofSeconds(60));

        assertNotNull(body, "GetCards never returned a card handle after pairing (no cards inserted?)");

        List<String> cards = elements(body, "Card"); // namespace-prefix agnostic
        assertTrue(!cards.isEmpty(), "GetCardsResponse held a CardHandle but no <Card> blocks: " + body);
        for (String card : cards) {
            assertNotNull(firstMatch(card, "CardHandle"), "card without a CardHandle: " + card);
            String slot = firstMatch(card, "SlotId");
            assertNotNull(slot, "card without a SlotId: " + card);
            assertTrue(Integer.parseInt(slot.trim()) >= 1,
                    "SlotId must be >= 1 (slot-numbering fix), was " + slot);
        }
    }

    // ── process launch ───────────────────────────────────────────────────────────────────────────

    private Process startTerminal() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path")); // the surefire classpath holds app + transitives
        cmd.addAll(remoteJmxFlags(TERMINAL_JMX_PORT));
        cmd.add(EHEALTH_KT_MAIN);
        cmd.add("--port");
        cmd.add(Integer.toString(TERMINAL_PORT));
        cmd.add("--ui");
        cmd.add("HEADLESS");
        cmd.add("--jmx"); // registers the EhealthKtManagement bean on the platform MBean server
        cmd.add("--pairing-file");
        cmd.add(tempDir.resolve("pairing.json").toString());
        cmd.add("--tsl-cache-dir");
        cmd.add(tempDir.resolve("tsl-cache").toString());
        cmd.add("--terminal-name");
        cmd.add("eHealth-KT-systest");
        return start(cmd, tempDir.resolve("ehealth-kt.log"));
    }

    private Process startKonnektor(Path runnerJar) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin());
        // The gematik conn WSDLs import xmldsig-core-schema.xsd, which carries an external-DTD
        // DOCTYPE. CXF re-parses these schemas when it creates the SOAP endpoints at boot, and
        // JAXP's default accessExternalDTD/Schema policy ("") blocks that read, failing endpoint
        // creation. The reactor build/dev get this via .mvn/jvm.config; a standalone runner jar
        // does not, so pass it here (mirrors gemSpec runtime requirements for the konnektor).
        cmd.add("-Djavax.xml.accessExternalDTD=all");
        cmd.add("-Djavax.xml.accessExternalSchema=all");
        cmd.addAll(remoteJmxFlags(KONN_JMX_PORT));
        cmd.add("-Dquarkus.profile=dev"); // dev profile disables OIDC and opens Hawtio (matches local dev)
        cmd.add("-Dquarkus.http.port=" + KONN_HTTP_PORT);
        // db-kind and the schema-management strategy are baked into the runner jar (build-time);
        // only the jdbc.url is overridable at runtime, so point it at a throwaway in-memory DB.
        cmd.add("-Dquarkus.datasource.jdbc.url=jdbc:h2:mem:sicct-systest;DB_CLOSE_DELAY=-1");
        cmd.add("-jar");
        cmd.add(runnerJar.toString());
        return start(cmd, tempDir.resolve("konnektor.log"));
    }

    private static List<String> remoteJmxFlags(int port) {
        return List.of(
                "-Dcom.sun.management.jmxremote=true",
                "-Dcom.sun.management.jmxremote.port=" + port,
                "-Dcom.sun.management.jmxremote.rmi.port=" + port,
                "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-Dcom.sun.management.jmxremote.local.only=false",
                "-Djava.rmi.server.hostname=127.0.0.1");
    }

    private static Process start(List<String> cmd, Path logFile) throws IOException {
        return new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Locate {@code quarkus-server/target/quarkus-app/quarkus-run.jar} (or a system-property override). */
    private static Path locateKonnektorRunnerJar() {
        String override = System.getProperty("systemtest.konnektor.runner.jar");
        if (override != null && Files.isRegularFile(Path.of(override))) {
            return Path.of(override);
        }
        // surefire runs with user.dir = the system-tests module directory.
        for (String rel : new String[] {
                "../quarkus-server/target/quarkus-app/quarkus-run.jar",
                "quarkus-server/target/quarkus-app/quarkus-run.jar" }) {
            Path p = Path.of(rel).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    // ── JMX helpers ──────────────────────────────────────────────────────────────────────────────

    private JMXConnector jmxConnect(int port) throws IOException {
        JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
        return JMXConnectorFactory.connect(url, null);
    }

    private static String invokeStr(MBeanServerConnection mbsc, ObjectName name, String op, String arg)
            throws Exception {
        Object r = mbsc.invoke(name, op, new Object[] { arg }, new String[] { "java.lang.String" });
        return r == null ? "" : r.toString();
    }

    private static boolean getBool(MBeanServerConnection mbsc, ObjectName name, String attr) {
        try {
            return Boolean.TRUE.equals(mbsc.getAttribute(name, attr));
        } catch (Exception e) {
            return false;
        }
    }

    private static int getInt(MBeanServerConnection mbsc, ObjectName name, String attr) throws Exception {
        return ((Number) mbsc.getAttribute(name, attr)).intValue();
    }

    /** Returns the CTID of the (single) discovered terminal, or {@code null} if none is known yet. */
    private String discoverCtid() {
        try {
            String all = invokeStr(konn, new ObjectName(KONN_CONN_MBEAN), "getTerminalStatus", "eHealth-KT-systest");
            String id = jsonField(all, "ctid");
            if (id != null) {
                return id;
            }
        } catch (Exception ignored) {
            // fall through to the list form
        }
        try {
            Object r = konn.invoke(new ObjectName(KONN_CONN_MBEAN), "listAllTerminals", new Object[0], new String[0]);
            Matcher m = Pattern.compile("ctid=([0-9a-fA-F-]{36})").matcher(r == null ? "" : r.toString());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── SOAP GetCards ────────────────────────────────────────────────────────────────────────────

    private HttpResponse<String> getCards() {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="%s" xmlns:ctx="%s" xmlns:cc="%s">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <evt:GetCards xmlns:evt="%s">
                      <ctx:Context>
                        <cc:MandantId>Mandant1</cc:MandantId>
                        <cc:ClientSystemId>ClientSystem1</cc:ClientSystemId>
                        <cc:WorkplaceId>Workplace1</cc:WorkplaceId>
                        <cc:UserId>User1</cc:UserId>
                      </ctx:Context>
                    </evt:GetCards>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(NS_SOAP, NS_CTX, NS_CCOMMON, NS_EVENT);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + KONN_HTTP_PORT + "/ws/conn/EventService"))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", '"' + NS_EVENT + "#GetCards" + '"')
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(envelope))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    // ── small generic helpers ────────────────────────────────────────────────────────────────────

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
    }

    private int httpStatus(String url) {
        try {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean portOpen(int port) {
        try (var s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean waitFor(BooleanSupplier cond, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            sleep(500);
        }
        return cond.getAsBoolean();
    }

    private static <T> T waitForValue(java.util.function.Supplier<T> supplier, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T v = supplier.get();
            if (v != null) {
                return v;
            }
            sleep(500);
        }
        return supplier.get();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Value of a {@code "field":"value"} pair in a flat JSON string, or {@code null}. */
    private static String jsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Text content of the first element with the given local name (ignoring any namespace prefix). */
    private static String firstMatch(String xml, String localName) {
        Matcher m = Pattern.compile(
                        "<(?:[\\w.-]+:)?" + Pattern.quote(localName) + "\\b[^>]*>(.*?)</(?:[\\w.-]+:)?"
                                + Pattern.quote(localName) + ">",
                        Pattern.DOTALL)
                .matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Inner content of every {@code <localName>…</localName>} element, ignoring any namespace prefix. */
    private static List<String> elements(String xml, String localName) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                        "<(?:[\\w.-]+:)?" + Pattern.quote(localName) + "\\b[^>]*>(.*?)</(?:[\\w.-]+:)?"
                                + Pattern.quote(localName) + ">",
                        Pattern.DOTALL)
                .matcher(xml);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void destroyQuietly(Process p) {
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private static void closeQuietly(JMXConnector c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
