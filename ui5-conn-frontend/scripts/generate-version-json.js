/*
 * Generates resources/sap-ui-version.json for the offline self-contained build.
 *
 * UI5 Tooling never emits this file (only SAP's official dist build does), yet UI5
 * requests resources/sap-ui-version.json at startup via sap/ui/VersionInfo. Without
 * it the browser logs a 404. We synthesise it from the framework version declared in
 * ui5.yaml and the libraries actually copied into the build (each dir holding a
 * .library descriptor), so it stays in sync with what was bundled.
 */
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const distResources = path.join(
  root,
  "target/classes/META-INF/resources/conn-ui/resources"
);

function frameworkVersion() {
  const yaml = fs.readFileSync(path.join(root, "ui5.yaml"), "utf8");
  const m = yaml.match(/version:\s*["']?([0-9]+\.[0-9]+\.[0-9]+)["']?/);
  if (!m) throw new Error("Could not read framework version from ui5.yaml");
  return m[1];
}

// Find every library in the build: a directory containing a ".library" file.
function findLibraries(dir, baseLen, acc) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory()) {
      if (entry.name === ".library") {
        const rel = dir.slice(baseLen + 1).split(path.sep).join(".");
        if (rel) acc.add(rel);
      }
      continue;
    }
    findLibraries(path.join(dir, entry.name), baseLen, acc);
  }
  return acc;
}

if (!fs.existsSync(distResources)) {
  throw new Error("Build output not found: " + distResources);
}

const version = frameworkVersion();
const libs = [...findLibraries(distResources, distResources.length, new Set())]
  .sort()
  .map((name) => ({ name, version }));

const versionInfo = {
  name: "openui5",
  version,
  buildTimestamp: new Date().toISOString().replace(/[-:T]/g, "").slice(0, 12),
  scmRevision: "",
  libraries: libs,
};

const out = path.join(distResources, "sap-ui-version.json");
fs.writeFileSync(out, JSON.stringify(versionInfo, null, 2) + "\n");
console.log(`Wrote ${out} (${libs.length} libraries, version ${version})`);
