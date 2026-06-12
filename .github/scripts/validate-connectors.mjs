#!/usr/bin/env node
// Validates the declarative connector catalog under connectors/ (issue #334).
// Lightweight, dependency-free checks mirroring connectors/schema/connector.schema.json:
//   - each connectors/<id>/connector.json parses and its `id` equals the folder name
//   - required fields are present; dbType/sslMode are in the allowed sets
//   - non-bundled connectors declare a maven|url driver with a 64-hex sha256
//   - the referenced logo file exists in the connector folder
// Exits non-zero (listing every problem) when anything is off.
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(process.cwd(), 'connectors');
const DB_TYPES = ['POSTGRESQL', 'MYSQL', 'MARIADB', 'ORACLE', 'MSSQL', 'CUSTOM', 'MONGODB', 'COUCHBASE', 'REDIS', 'CASSANDRA', 'SCYLLADB', 'ELASTICSEARCH', 'OPENSEARCH'];
const CATEGORIES = ['RELATIONAL', 'DOCUMENT', 'KEY_VALUE', 'WIDE_COLUMN', 'SEARCH', 'GRAPH'];
const SSL_MODES = ['DISABLE', 'REQUIRE', 'VERIFY_CA', 'VERIFY_FULL'];
const SHA256 = /^[0-9a-f]{64}$/;
const ID = /^[a-z0-9][a-z0-9-]*$/;

const problems = [];
const fail = (id, msg) => problems.push(`[${id}] ${msg}`);

const entries = readdirSync(ROOT, { withFileTypes: true })
  .filter((d) => d.isDirectory() && d.name !== 'schema')
  .map((d) => d.name);

if (entries.length === 0) {
  console.error('No connector folders found under connectors/');
  process.exit(1);
}

for (const folder of entries) {
  const manifestPath = path.join(ROOT, folder, 'connector.json');
  if (!existsSync(manifestPath)) {
    fail(folder, 'missing connector.json');
    continue;
  }
  let m;
  try {
    m = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (e) {
    fail(folder, `connector.json is not valid JSON: ${e.message}`);
    continue;
  }
  if (m.schemaVersion !== 1) fail(folder, `schemaVersion must be 1 (got ${m.schemaVersion})`);
  if (!ID.test(m.id ?? '')) fail(folder, `id "${m.id}" is not a valid slug`);
  if (m.id !== folder) fail(folder, `id "${m.id}" does not match folder "${folder}"`);
  if (!m.name) fail(folder, 'name is required');
  if (!DB_TYPES.includes(m.dbType)) fail(folder, `dbType "${m.dbType}" is invalid`);
  if (m.category != null && !CATEGORIES.includes(m.category)) fail(folder, `category "${m.category}" is invalid`);
  if (!SSL_MODES.includes(m.defaultSslMode)) fail(folder, `defaultSslMode "${m.defaultSslMode}" is invalid`);
  if (typeof m.defaultPort !== 'number') fail(folder, 'defaultPort must be a number');
  // Engine-managed (non-RELATIONAL, i.e. NoSQL) connectors are native, not JDBC —
  // jdbcUrlTemplate/driverClassName are required only for the default RELATIONAL category.
  const isEngineManaged = m.category != null && m.category !== 'RELATIONAL';
  if (!isEngineManaged && !m.jdbcUrlTemplate) fail(folder, 'jdbcUrlTemplate is required');
  if (!isEngineManaged && !m.driverClassName) fail(folder, 'driverClassName is required');
  if (typeof m.bundled !== 'boolean') fail(folder, 'bundled must be a boolean');
  if (!m.logo) {
    fail(folder, 'logo is required');
  } else if (!existsSync(path.join(ROOT, folder, m.logo))) {
    fail(folder, `logo file "${m.logo}" does not exist`);
  }
  if (m.bundled) {
    if (m.driver) fail(folder, 'bundled connector must not declare a driver');
  } else {
    const d = m.driver;
    if (!d) {
      fail(folder, 'non-bundled connector must declare a driver');
    } else if (d.type === 'maven') {
      if (!d.groupId || !d.artifactId || !d.version) fail(folder, 'maven driver requires groupId/artifactId/version');
      if (!SHA256.test(d.sha256 ?? '')) fail(folder, 'maven driver sha256 must be 64 hex chars');
    } else if (d.type === 'url') {
      if (!d.url || !d.fileName) fail(folder, 'url driver requires url/fileName');
      if (!SHA256.test(d.sha256 ?? '')) fail(folder, 'url driver sha256 must be 64 hex chars');
    } else {
      fail(folder, `driver.type "${d.type}" must be "maven" or "url"`);
    }
  }
  // Logo must also be served statically by the frontend (existing convention).
  const servedLogo = path.resolve(ROOT, '..', 'frontend', 'public', 'db-icons', `${m.id}.svg`);
  if (!existsSync(servedLogo)) {
    fail(folder, `served logo frontend/public/db-icons/${m.id}.svg is missing`);
  }
}

// Ensure the JSON Schema itself is present and parseable.
const schemaPath = path.join(ROOT, 'schema', 'connector.schema.json');
if (!existsSync(schemaPath)) {
  problems.push('[schema] connectors/schema/connector.schema.json is missing');
} else {
  try {
    JSON.parse(readFileSync(schemaPath, 'utf8'));
  } catch (e) {
    problems.push(`[schema] connector.schema.json is not valid JSON: ${e.message}`);
  }
}

if (problems.length > 0) {
  console.error('Connector manifest validation failed:');
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`Validated ${entries.length} connector manifest(s): ${entries.join(', ')}`);
