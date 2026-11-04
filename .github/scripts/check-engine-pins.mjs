#!/usr/bin/env node
// Verifies every engine plugin's reproducible-build pin (issue #418, generalizing #414).
// Convention: the engines/<id>/ directory basename IS the connector id, so each built shaded jar
// engines/<id>/target/*-all.jar must match connectors/<id>/connector.json -> driver.{sha256,fileName}.
// Drift means the engine (or a core.api type it compiles against) changed without bumping the
// plugin version and re-pinning — see docs/15-engine-sdk.md. Run AFTER building engines/*.
// Also enforces a size ceiling: the release pushes every jar to gh-pages, and GitHub rejects any
// file over 100 MiB, so a driver bump that crosses it must fail here, not at release time.
// Exits non-zero (listing every problem) when anything is off.
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import path from 'node:path';

const ENGINES = path.resolve(process.cwd(), 'engines');
const CONNECTORS = path.resolve(process.cwd(), 'connectors');

// 5 MiB below GitHub's hard 100 MiB per-file limit, so the next bump warns before it breaks.
const MAX_JAR_BYTES = 95 * 1024 * 1024;
const mib = (bytes) => (bytes / 1024 / 1024).toFixed(2);

const problems = [];
const fail = (id, msg) => problems.push(`[${id}] ${msg}`);

// Optional positional args restrict the check to specific engine ids — used by the
// CI engines matrix, where each leg builds only its own plugin. No args = check every
// engine (the release workflow path, which builds them all).
const only = new Set(process.argv.slice(2));

let engineDirs = readdirSync(ENGINES, { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => d.name);

if (engineDirs.length === 0) {
  console.error('No engine plugin folders found under engines/');
  process.exit(1);
}

if (only.size > 0) {
  const unknown = [...only].filter((id) => !engineDirs.includes(id));
  if (unknown.length > 0) {
    console.error(`Unknown engine id(s): ${unknown.join(', ')} — expected one of: ${engineDirs.join(', ')}`);
    process.exit(1);
  }
  engineDirs = engineDirs.filter((id) => only.has(id));
}

for (const id of engineDirs) {
  const targetDir = path.join(ENGINES, id, 'target');
  const jars = existsSync(targetDir)
    ? readdirSync(targetDir).filter((f) => f.endsWith('-all.jar'))
    : [];
  if (jars.length !== 1) {
    fail(id, `expected exactly one engines/${id}/target/*-all.jar (found ${jars.length}) — build the plugin first`);
    continue;
  }
  const jarName = jars[0];
  const size = statSync(path.join(targetDir, jarName)).size;
  if (size > MAX_JAR_BYTES) {
    fail(id, `${jarName} is ${mib(size)} MiB, over the ${mib(MAX_JAR_BYTES)} MiB ceiling — the release cannot push it to gh-pages (GitHub rejects files over 100 MiB); trim the shaded jar`);
  }
  const built = createHash('sha256')
    .update(readFileSync(path.join(targetDir, jarName)))
    .digest('hex');

  const manifestPath = path.join(CONNECTORS, id, 'connector.json');
  if (!existsSync(manifestPath)) {
    fail(id, `connectors/${id}/connector.json is missing (engines/<id> basename must equal the connector id)`);
    continue;
  }
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (e) {
    fail(id, `connectors/${id}/connector.json is not valid JSON: ${e.message}`);
    continue;
  }
  const driver = manifest.driver ?? {};
  if (driver.sha256 !== built) {
    fail(id, `sha256 pin drift — built ${built}, pinned ${driver.sha256 ?? '(none)'}; bump the plugin version and re-pin`);
  }
  if (driver.fileName !== jarName) {
    fail(id, `fileName pin drift — built ${jarName}, pinned ${driver.fileName ?? '(none)'}`);
  }
  if (problems.length === 0 || !problems.some((p) => p.startsWith(`[${id}]`))) {
    console.log(`${id}: ${jarName} = ${built} (${mib(size)} MiB)`);
  }
}

if (problems.length > 0) {
  console.error('Engine plugin pin check failed:');
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`Verified ${engineDirs.length} engine plugin pin(s): ${engineDirs.join(', ')}`);
