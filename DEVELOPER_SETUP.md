# Developer Setup

This guide covers two paths: the **recommended Dev Container** (zero local tooling beyond Docker and VS Code / IntelliJ) and a **manual local setup** for developers who prefer a bare-metal environment.

---

## Table of Contents

- [Quick Start with Dev Container](#quick-start-with-dev-container)
- [Manual Local Setup](#manual-local-setup)
- [Build Reference](#build-reference)
- [Running the Application](#running-the-application)
- [IDE Configuration](#ide-configuration)
- [Regulatory and Traceability Notes](#regulatory-and-traceability-notes)

---

## Quick Start with Dev Container

The Dev Container definition at [`.devcontainer/devcontainer.json`](.devcontainer/devcontainer.json) pre-installs every dependency — Java 21, Maven, SoftHSM2, pcscd, and the Quarkus CLI — so the first build runs without any manual steps.

### Prerequisites

| Tool                            | Minimum version | Notes                                                                                                                       |
| ------------------------------- | --------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Docker Desktop or Docker Engine | 24+             | Engine mode on Linux; Desktop on macOS/Windows                                                                              |
| VS Code                         | 1.85+           | With the [Dev Containers](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension |
| Git                             | any             | Used for submodule checkout                                                                                                 |

IntelliJ IDEA Ultimate 2024.1+ supports Dev Containers natively via **File → Remote Development → Dev Containers**.

### Opening the Container

```bash
# Clone with submodules
git clone --recurse-submodules <repo-url>
cd basis-consumer-health

# VS Code: open the folder, then accept "Reopen in Container" prompt
# IntelliJ: File → Remote Development → Dev Containers → Select folder
```

The container build runs `postCreateCommand` which initialises SoftHSM2 and verifies the Maven wrapper. The first `mvn install` downloads ~300 MB of dependencies into a persisted volume so subsequent starts are fast.

---

## Manual Local Setup

Use this path if you are working on Linux or macOS without Docker, or if you need to attach a physical HSM or USB card reader.

### 1 — Java 21 (Eclipse Temurin)

The project targets **Java 21 LTS**. We recommend [Eclipse Temurin](https://adoptium.net/) distributed by the Adoptium working group.

```bash
# Ubuntu / Debian
sudo apt-get install -y wget apt-transport-https
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/trusted.gpg.d/adoptium.asc
echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update && sudo apt-get install -y temurin-21-jdk

# macOS (Homebrew)
brew install --cask temurin@21

# Verify
java -version   # must show 21.x
```

> **FIPS note**: Production deployments use a FIPS 140-2 Level 3 certified HSM (Utimaco). Development uses SoftHSM2 (step 3). Never configure SoftHSM2 outside of CI/developer environments.

### 2 — Maven 3.9+

```bash
# Ubuntu / Debian
sudo apt-get install -y maven

# macOS
brew install maven

# Verify
mvn -version   # must show 3.9.x or later
```

### 3 — SoftHSM2 (PKCS#11 emulation for tests)

SoftHSM2 replaces the physical HSM in CI and local test runs. It is **never** used in production.

```bash
# Ubuntu / Debian
sudo apt-get install -y softhsm2

# macOS
brew install softhsm

# Initialise a token for development (slot 0, PIN: 1234, SO-PIN: 0000)
softhsm2-util --init-token --slot 0 --label "dev-hsm" --pin 1234 --so-pin 0000

# Verify the token is visible
softhsm2-util --show-slots
```

The test resource manager in `Pkcs11CryptoProviderTest` discovers the SoftHSM2 library path automatically on Linux (`/usr/lib/softhsm/libsofthsm2.so`) and macOS (`/opt/homebrew/lib/softhsm/libsofthsm2.so`). Set `SOFTHSM2_LIB` if your path differs.

### 4 — PC/SC (card reader support)

Required only if you are testing the PC/SC adapter with a physical USB card reader. Can be skipped for SICCT/HSM-only development.

```bash
# Ubuntu / Debian
sudo apt-get install -y pcscd libpcsclite-dev

# Start the daemon
sudo systemctl enable --now pcscd
```

### 5 — Git Submodules (openkim / KIM)

The `openkim` KOM-LE client is a git submodule. It must be initialised before the first build.

```bash
git submodule update --init --recursive
```

If the submodule was not fetched during clone, run the above command from the repository root.

### 6 — Quarkus CLI (optional)

The Quarkus CLI provides a faster `quarkus dev` workflow and the extension scaffolding commands used when adding new Quarkus extensions.

```bash
# Via SDKMAN (recommended on Linux/macOS)
sdk install quarkus

# Verify
quarkus version
```

---

## Build Reference

All commands are run from the repository root.

| Goal                    | Command                                      |
| ----------------------- | -------------------------------------------- |
| Full build (skip tests) | `mvn install -DskipTests`                    |
| Full build + unit tests | `mvn verify`                                 |
| Single module build     | `mvn install -pl sicct-lib -am`              |
| Dev mode (hot reload)   | `mvn -pl basis-consumer-server quarkus:dev`  |
| Continuous test mode    | `mvn -pl basis-consumer-server quarkus:test` |
| Generate ASN.1 sources  | `mvn generate-sources -pl sicct-lib`         |
| Dependency tree         | `mvn dependency:tree`                        |
| OWASP dependency check  | `mvn org.owasp:dependency-check-maven:check` |

### Module Build Order

Maven resolves the dependency graph automatically, but for reference the logical build order is:

```
sicct-lib         (ASN.1 codegen + SICCT protocol types)
    ↓
signature-lib     (CryptoProvider CDI interface + P12/PKCS#11/PC/SC/SICCT adapters)
    ↓
sicct-server      (SICCT Quarkus extension — deployment + runtime)
    ↓
konnektor-server  (Apache CXF SOAP endpoints)
ldap-proxy-server (Netty LDAPv3 proxy)
    ↓
basis-consumer-server  (main Quarkus application — assembles all modules)
```

---

## Running the Application

### Dev Mode (recommended for local development)

```bash
mvn -pl basis-consumer-server quarkus:dev
```

Quarkus dev mode enables:
- Hot reload on source changes (no restart required)
- Dev UI at `http://localhost:8080/q/dev/`
- Hawtio management console at `http://localhost:8080/hawtio`
- SmallRye Health at `http://localhost:8080/q/health`

The embedded H2 database is initialised automatically on first start. No external database is required for development.

### Test Profile Configuration

Create `basis-consumer-server/src/main/resources/application-dev.properties` to override settings for local development without modifying the committed defaults:

```properties
# SoftHSM2 PKCS#11 library path (adjust if different on your system)
quarkus.pkcs11.library=/usr/lib/softhsm/libsofthsm2.so
quarkus.pkcs11.token-label=dev-hsm
quarkus.pkcs11.pin=1234

# H2 persistence — dev uses a local file store
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:target/dev-db;create=true

# Hawtio — disable authentication in dev
hawtio.authentication.enabled=false
```

### Running Tests

```bash
# All tests
mvn verify

# Single test class
mvn test -pl sicct-lib -Dtest=SicctTerminalConnectionTest

# With SoftHSM2 PKCS#11 (set library path explicitly)
SOFTHSM2_LIB=/usr/lib/softhsm/libsofthsm2.so mvn verify
```

---

## IDE Configuration

### IntelliJ IDEA

1. **Import**: `File → Open` → select the repository root `pom.xml` → "Open as Project"
2. **JDK**: `File → Project Structure → SDKs` → add Temurin 21
3. **Quarkus plugin**: `Settings → Plugins` → search "Quarkus" → install the JetBrains Quarkus plugin
4. **Run configuration**: use the "Quarkus" run configuration type targeting `basis-consumer-server`; it wraps `quarkus:dev` and attaches the debugger automatically

### VS Code

Install the recommended extension pack (accepted automatically when you open the workspace):

```json
// .vscode/extensions.json
{
  "recommendations": [
    "redhat.java",
    "redhat.vscode-quarkus",
    "vscjava.vscode-maven",
    "vscjava.vscode-java-debug",
    "vscjava.vscode-java-test",
    "jebbs.plantuml"
  ]
}
```

The Quarkus extension (`redhat.vscode-quarkus`) provides a one-click "Debug (Attach)" launch configuration for `quarkus:dev`.

#### PlantUML Preview in VS Code

The `jebbs.plantuml` extension renders `.puml` diagrams inline without a server. Add the following to your workspace settings (`.vscode/settings.json`):

```json
{
  "plantuml.render": "Local",
  "plantuml.java": "/usr/bin/java",
  "plantuml.diagramsRoot": "specs",
  "plantuml.exportOutDir": "specs",
  "plantuml.fileExtensions": ".puml"
}
```

Open any `.puml` file in [specs/001-quarkus-basis-consumer/diagrams/](specs/001-quarkus-basis-consumer/diagrams/) and press `Alt+D` (Windows/Linux) or `Option+D` (macOS) to open the live preview pane. The preview hot-reloads on every save.

> **Dependency**: the `Local` render mode requires Java to be on `PATH` (already satisfied if you have the JDK installed). No Graphviz installation is needed for sequence, use-case, and state diagrams; class and component diagrams benefit from having Graphviz installed (`sudo apt-get install graphviz` / `brew install graphviz`).

---

## PlantUML Diagrams on GitHub

The UML diagrams in [specs/001-quarkus-basis-consumer/diagrams/](specs/001-quarkus-basis-consumer/diagrams/) are plain `.puml` files. GitHub does not render PlantUML natively, but the **PlantUML for GitHub** browser extension adds inline rendering directly on github.com with no server-side setup.

### Browser Extension Installation

| Browser               | Extension                  | Install                                                                                                        |
| --------------------- | -------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Chrome / Edge / Brave | PlantUML Viewer for GitHub | [Chrome Web Store](https://chrome.google.com/webstore/detail/plantuml-viewer/legbfeljfbjgfifnkmpoajgpgejojooj) |
| Firefox               | PlantUML Viewer for GitHub | [Firefox Add-ons](https://addons.mozilla.org/en-US/firefox/addon/plantuml-viewer/)                             |

Alternatively, install the community extension directly from the source repository: [https://github.com/plantuml/plantuml-for-github](https://github.com/plantuml/plantuml-for-github).

After installation, open any `.puml` file on GitHub and the diagram renders automatically beneath the raw source — no configuration required.

### Available Diagrams

| File                                                                                                                 | Type       | What it shows                                        |
| -------------------------------------------------------------------------------------------------------------------- | ---------- | ---------------------------------------------------- |
| [`use-case.puml`](specs/001-quarkus-basis-consumer/diagrams/use-case.puml)                                           | Use Case   | Actors and system boundaries                         |
| [`deployment.puml`](specs/001-quarkus-basis-consumer/diagrams/deployment.puml)                                       | Deployment | K8s namespaces, HSM, SICCT terminals, VZD            |
| [`component.puml`](specs/001-quarkus-basis-consumer/diagrams/component.puml)                                         | Component  | Maven module dependencies                            |
| [`sequence-ehealth-authenticate.puml`](specs/001-quarkus-basis-consumer/diagrams/sequence-ehealth-authenticate.puml) | Sequence   | EHEALTH AUTHENTICATE (pairing, session, maintenance) |
| [`sequence-signing.puml`](specs/001-quarkus-basis-consumer/diagrams/sequence-signing.puml)                           | Sequence   | SignDocument SOAP → CryptoProvider → SICCT           |
| [`sequence-remote-pin.puml`](specs/001-quarkus-basis-consumer/diagrams/sequence-remote-pin.puml)                     | Sequence   | Remote-PIN via gSMC-KT trusted channel               |
| [`state-sicct-terminal.puml`](specs/001-quarkus-basis-consumer/diagrams/state-sicct-terminal.puml)                   | State      | CT correlation state machine                         |
| [`state-crypto-provider.puml`](specs/001-quarkus-basis-consumer/diagrams/state-crypto-provider.puml)                 | State      | CryptoProvider availability states                   |
| [`class-domain.puml`](specs/001-quarkus-basis-consumer/diagrams/class-domain.puml)                                   | Class      | Domain model and adapter hierarchy                   |

---

## Regulatory and Traceability Notes

This project is developed under the **gematik SSDLC** and **gemKPT_Test V3.6.0** test lifecycle. The following conventions are mandatory for all contributions:

### Afo Traceability

Every functional requirement in the spec carries a gematik Afo ID (e.g., `TIP1-A_5012`). Implementation commits and tests are linked to these IDs:

- **One commit per Afo**: each commit message must reference the Afo ID it satisfies, e.g.:  
  `feat(sicct): implement shared secret generation [TIP1-A_3244]`
- **Named tests**: test methods that cover a specific Afo must be named `test_<AfoId>_<description>`, e.g.:  
  `void test_TIP1_A_5012_remotePin_via_gsmckt_trusted_channel()`

### Dependency Hygiene

Any new Maven dependency added to the project requires:
1. A justification comment in the relevant `pom.xml`
2. An SBOM entry update (A_27431)
3. A security review before merging (OWASP dependency-check must pass)

### Security Constraints

- Private key bytes must **never** appear in JVM heap dumps, logs, or network traces (A_17598).
- SoftHSM2 may only be used in development and CI environments.
- The production HSM must be FIPS 140-2 Level 3 or CC EAL4 certified.
- All SOAP services require mutual TLS in production; dev mode accepts one-way TLS.
