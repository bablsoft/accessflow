---
name: add-engine
description: Add a new AccessFlow engine plugin under engines/<id>/ — scaffold the Maven project and QueryEngine SPI implementation, pin the shaded JAR in connectors/<id>/connector.json, wire DbType + migration + credential gates + i18n + frontend registration + docs/website, and open a PR. Trigger when the user says "add a <X> engine", "support <X> as a datasource", "new engine plugin", "implement the AF-N engine issue", or names a database AccessFlow does not yet support.
---

# add-engine

Adding an engine is ~12 areas of wiring, most of it outside `engines/`. The plugin itself is the
easy half; the half that gets forgotten is `DbType` → migration → credential gates → i18n ×7 →
frontend → docs → website → CI matrix.

**Read first, in order:** [`docs/15-engine-sdk.md`](../../../docs/15-engine-sdk.md) (authoritative —
the SPI surface, `QueryEngineContext` semantics, and the checklist at its end),
[`.claude/patterns/engine-plugin.md`](../../patterns/engine-plugin.md), and
[`.claude/patterns/engine-fanout.md`](../../patterns/engine-fanout.md).

## Inputs

Ask for anything not supplied — do not guess:

- **engine id** (lowercase; it is simultaneously the folder name, the connector id, and the
  `engineId()` return value — all automation derives from it)
- **display name**, **vendor**, **documentation URL**
- **`DbType` value** (SCREAMING_SNAKE) and **`category`** —
  `WAREHOUSE` | `DOCUMENT` | `KEY_VALUE` | `WIDE_COLUMN` | `SEARCH` | `GRAPH`
- **driver coordinates**, or "REST API, no driver" (the `engines/databricks/` shape)
- **default port** and **default SSL mode**
- **connection model** — host/port/db/user/pass, or cloud credentials (region + keys, service
  account JSON, workspace host + token)
- **a Testcontainers image**, or "none" (the Snowflake precedent: unit + mocked-facade tests only)

## Pick the closest existing engine and follow it

| Shape | Follow |
|---|---|
| SQL dialect over JDBC | `engines/snowflake/` |
| SQL dialect over REST, no vendor driver at all | `engines/databricks/` |
| Cloud credentials instead of host/port | `engines/dynamodb/` |
| Document | `engines/mongodb/` |
| Key-value | `engines/redis/` |
| Graph | `engines/neo4j/` |

## Workflow

### 1. Build the host jar the plugin compiles against

```bash
mvn -f backend/pom.xml install -DskipTests
```

### 2. Scaffold `engines/<id>/pom.xml`

Non-negotiables (copy from the reference engine): its **own** `<version>` starting at `1.0.0`;
`maven.compiler.release` 25; a fixed `project.build.outputTimestamp`; `accessflow` and `slf4j-api`
at **`provided`** scope; shade with `shadedArtifactAttached=true`, classifier `all`, and every
third-party library except the vendor driver relocated under
`com.bablsoft.accessflow.engine.<id>.shaded.*`; surefire + failsafe wired.

### 3. Implement the SPI

`<Id>QueryEngine implements QueryEngine` — `final`, **public no-arg constructor** (ServiceLoader
requires it), and `initialize(QueryEngineContext)` doing the wiring Spring DI would otherwise do.
Alongside it: `<Id>CommandParser`/`Classifier`, `<Id>QueryExecutor`, `<Id>RowSecurityApplier`,
`<Id>SchemaIntrospector`, `<Id>ExceptionTranslator`, `<Id>EngineSettings`, `<Id>ConnectionProbe`.

**Row security fails closed** on any shape you cannot provably rewrite — that is the product
promise, not a nicety. Masking goes through the shared `core.api.ColumnMasker`. Handle unary
operators (`IS_NULL`) *before* any empty-values deny-all guard.

### 4. Register with ServiceLoader

`src/main/resources/META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine`, containing
the impl FQCN.

### 5. Three test tiers

Unit (parser / settings / translator / result mapper / row-security applier), a Testcontainers
facade IT driving the whole SPI, and `ShadedJarServiceLoaderIT` loading the built `*-all.jar` in
an isolated `URLClassLoader`.

### 6. Build and pin — in the same commit

```bash
mvn -f engines/<id>/pom.xml clean package
shasum -a 256 engines/<id>/target/*-all.jar
```

Write `connectors/<id>/connector.json` (`schemaVersion`, `id`, `name`, `dbType`, `category`,
`vendor`, `description`, `documentationUrl`, `logo`, `defaultPort`, `defaultSslMode`,
`bundled: false`, and `driver.{type,url,fileName,sha256}`) plus `connectors/<id>/logo.svg`.

```bash
node .github/scripts/validate-connectors.mjs
node .github/scripts/check-engine-pins.mjs <id>
```

The `url` points at the eventual `gh-pages` release path; publishing is `release.yml`'s job, and
CI verifies the SHA against the locally built jar. Say so rather than trying to upload anything.

### 7. Backend wiring

- New value in `core.api.DbType`.
- Migration `V<max+1>__add_<id>_db_type.sql` = `ALTER TYPE db_type ADD VALUE IF NOT EXISTS '<VALUE>';`
  **plus** the `V<max+1>__add_<id>_db_type.sql.conf` sidecar with `executeInTransaction=false`.
  Find `max` with:
  `ls backend/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -rn | head -1`
- A throwing case in `core/internal/DefaultJdbcCoordinatesFactory` (engine-managed types are not
  JDBC-pooled).
- **Both credential gates in `DatasourceAdminServiceImpl`, not just one** — `validateDriverChoice`
  *and* `validateCredentials`. A non-username connection model (cloud keys, bearer token,
  service-account JSON) needs a branch in each; missing the second is a known trap.
- Dispatchers need nothing — they key off `QueryEngineCatalog.isEngineManaged(dbType)`.

### 8. i18n

`error.<id>.*` keys in `messages.properties` **and all six** locale files.

### 9. Frontend registration

`DbType` union in `types/api.ts`; an entry in `utils/engineModes.ts` (syntaxes, highlight
language, `canFormat`, `supportsTextToSql`, `defaultResultView`) and in `DB_TYPE_COLOR`;
`enums.db_type.<VALUE>` in `en.json` + the six other locales;
`components/datasources/DatasourceIcon.tsx` + `public/db-icons/<id>.svg`.

### 10. Docs and website

A row in `docs/14-connectors.md`, an engine section in `docs/05-backend.md`, the enum note in
`docs/03-data-model.md`, the connector grid in `website/index.html` and `website/docs/index.html`
— **and bump `website/sitemap.xml` `<lastmod>` plus the JSON-LD `dateModified`** on every page you
touch. Supported-databases copy in `README.md`, and the category table in `CLAUDE.md`.

### 11. CI

Add the id to the `engines` job matrix in `.github/workflows/ci.yml`.

### 12. Optional per-engine tuning

Document the config keys only. Operators reach them as
`ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>` with zero host code — do not add host-side properties.

## Definition of done

- [ ] `mvn -f engines/<id>/pom.xml clean verify` green (unit + facade IT + ServiceLoader IT)
- [ ] `node .github/scripts/check-engine-pins.mjs <id>` and `validate-connectors.mjs` clean
- [ ] `mvn -q -f backend/pom.xml test -Dtest='ApplicationModulesTest,ApiPackageDependencyTest,MessagesParityTest'` green
- [ ] `cd frontend && npm run typecheck && npm run test:coverage` green (locale parity included)
- [ ] Every item in `docs/15-engine-sdk.md` → "Checklist: adding a new engine" ticked
- [ ] Branch `feature/AF-<n>-<id>-engine`, PR references the issue

## Out of scope

- **Publishing the JAR.** `release.yml` uploads it to `gh-pages`; the manifest's `url` is the
  eventual location and CI verifies the SHA locally.
- **Bumping the plugin version in `release.yml`.** Engine version lines are independent and
  `versions:set` never touches them.
- **Session tokens / STS assumed roles** for cloud engines — out of scope in v1, matching DynamoDB.
