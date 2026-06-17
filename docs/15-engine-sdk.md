# 15 — Engine-Plugin SDK

How to author a **query-engine plugin**: a native (non-JDBC) database engine — MongoDB, Couchbase,
Redis, Cassandra/ScyllaDB, Elasticsearch/OpenSearch, Amazon DynamoDB, and Neo4j today — that plugs into AccessFlow as
*plugin project + manifest entry*, with **no changes** to `DefaultQueryEngineCatalog`, the proxy
dispatchers, CI workflows, or the release workflow (AF-414 / AF-418). The reference implementation
is [`engines/mongodb/`](../engines/mongodb/); [`engines/couchbase/`](../engines/couchbase/)
(AF-412) is the first engine built purely against this SDK — and the SQL-shaped counterexample
(SQL++ classifier + WHERE-splice row security instead of `$match` injection);
[`engines/redis/`](../engines/redis/) (AF-419) is the `KEY_VALUE` reference — a command allow-list,
key-prefix `referencedTables`, and fail-closed row security where row predicates have no meaning;
[`engines/cassandra/`](../engines/cassandra/) (AF-421) is the `WIDE_COLUMN` reference — a CQL
classifier with **key-aware** WHERE-splice row security (only partition/clustering-key predicates
are spliced; non-key columns fail closed rather than injecting `ALLOW FILTERING`), and the example
of **one JAR backing two connectors**: it registers two `QueryEngine` providers (`engineId`
`cassandra` and `scylladb`), so the same shaded JAR serves both the `cassandra` and `scylladb`
connectors (ScyllaDB being CQL-compatible). A new `DbType` is needed per connector because the
catalog allows one connector per non-`CUSTOM` dialect, but the engine code is shared.
[`engines/dynamodb/`](../engines/dynamodb/) (AF-422) is the `KEY_VALUE` PartiQL reference — and the
example of an engine whose **connection is cloud credentials + region rather than host/port**
(`database_name`=region, `username`/`password`=access key/secret, `jdbc_url_override`=optional
endpoint), exercising the SDK's flexibility beyond the host/port assumption.
[`engines/neo4j/`](../engines/neo4j/) (AF-423) is the `GRAPH` Cypher reference — the engine whose
row-level security is **not** a SQL-style WHERE-after-FROM but a predicate ANDed onto each
`MATCH`'s `WHERE` (scoped by the bound node variable of the policied label; fail-closed on anonymous
or write shapes) — and the example of an engine that accepts **either** host/port **or** a full
`bolt://` / `neo4j+s://` URI override.

Related chapters: [05-backend.md → MongoDB engine](./05-backend.md#mongodb-engine) (how the host
dispatches to engines), [14-connectors.md](./14-connectors.md) (the connector catalog the plugin is
delivered through).

## The SPI surface

A plugin implements **`com.bablsoft.accessflow.core.api.QueryEngine`**:

| Method | Purpose |
|--------|---------|
| `engineId()` | Stable id; **must equal the connector id and the `engines/<id>/` folder name** |
| `initialize(QueryEngineContext)` | One-time wiring from host capabilities (called once, before any other method) |
| `parse(query)` | Validate + classify a submitted query → engine-neutral `SqlParseResult` |
| `execute(QueryEngineExecutionRequest)` | Run an approved query with the host-computed row cap / timeout → `QueryExecutionResult` |
| `sampleTable(QueryEngineSampleRequest)` | Read a bounded, governance-applied sample of one table/collection (AF-443) — issue the engine's native "read all rows, capped at N" and funnel it through the same row-security + masking path as `execute` → `SelectExecutionResult`. Engines whose row-security model has no per-row meaning (key-value prefixes) must **fail closed** when a directive applies. |
| `testConnection(descriptor)` | Admin "Test connection" → `ConnectionTestResult` |
| `introspectSchema(descriptor)` | Schema/collection sampling → `DatabaseSchemaView` (drives the ER diagram, autocomplete, AI schema context) |
| `evictDatasource(id)` | Drop cached clients when a datasource's config changes or it is deactivated |
| `shutdown()` | Optional cleanup (default no-op) |

Everything is defined over the api-pure `core.api` DTOs (`SqlParseResult`,
`QueryExecutionRequest`/`QueryExecutionResult`, `ConnectionTestResult`, `DatabaseSchemaView`,
`DatasourceConnectionDescriptor`, `RowSecurityDirective`, `ColumnMaskDirective`) plus the shared
`core.api.ColumnMasker` helper. Exceptions cross the boundary as the concrete `core.api` types
(`InvalidSqlException` → HTTP 422, `QueryExecutionFailedException`,
`QueryExecutionTimeoutException`, `DatasourceConnectionTestException`) so host error handling stays
engine-agnostic.

**Purity rules.** A plugin may use only the backend's `core.api` surface plus its own shaded
dependencies. No Spring, no Lombok, no host-internal types — the plugin runs in an isolated
classloader and is wired manually inside `initialize(...)`.

**Feature parity bar.** An engine is expected to enforce the same governance guarantees as the SQL
path: query-type classification onto the existing `QueryType` model (so permissions, routing
policies, and approval plans apply unchanged), row-level security translated into the engine's
native filtering (MongoDB: `$match` injection), post-fetch field masking via the shared
`ColumnMasker`, fail-closed behaviour for unparseable or dangerous constructs (server-side
JavaScript, write-exfiltration operators), and read-replica routing where the engine supports it.

## `QueryEngineContext` semantics

`initialize` receives the host capabilities that replace Spring DI:

- **`EngineMessages messages`** — message resolution backed by the host `MessageSource` with the
  calling thread's locale. **i18n key ownership:** the engine's `error.<engine>.*` keys live in the
  *host's* `messages.properties` (and every `messages_<locale>.properties` — `MessagesParityTest`
  applies). Unknown keys degrade to the key itself, so a plugin newer than the host fails soft.
- **`CredentialDecryptor credentials`** — decrypts `password_encrypted` on demand. Never store the
  plaintext beyond client construction.
- **`Map<String, String> config`** — the engine's tuning config, bound by the host from
  `accessflow.proxy.engines.<connector-id>.*` (`EngineConfigProperties`). Key names are the
  *engine's own contract* — the host never interprets them; use kebab-case
  (`connect-timeout`, `max-pool-size`, …). Operators set them as
  `ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>` env vars (relaxed binding; `_`/`.` in the key normalize to
  `-`, and generic env vars override `application.yml` defaults). **Plugins must own their
  defaults**: an absent or empty map must yield a working engine. The pre-AF-418
  `ACCESSFLOW_PROXY_MONGO_*` names remain supported as aliases via `application.yml` placeholders.
- **`Clock clock`** — the host UTC clock. Use it for all timing; never `Instant.now()` directly.

## Project conventions

- **Standalone Maven project** at `engines/<id>/` — deliberately *outside* the backend application.
  The folder basename, the connector id, and `engineId()` must all be the same string; CI and the
  release workflow derive everything from it.
- **Compiles against the backend's plain JAR** (`com.bablsoft.accessflow:accessflow`, `provided`
  scope). The backend's `spring-boot-maven-plugin` uses `<classifier>exec</classifier>` so
  `mvn -f backend/pom.xml install` publishes the plain classes.
- **Own version line.** The plugin version is independent of the application release version —
  `release.yml` never runs `versions:set` on it. A published JAR is immutable at its version.
- **Shaded + relocated.** The deliverable is one self-contained `*-all.jar`
  (`maven-shade-plugin`, `shadedArtifactAttached=true`, classifier `all`) bundling the native
  driver; relocate shared libraries (Jackson, micrometer) under
  `com.bablsoft.accessflow.engine.<id>.shaded.*` so nothing leaks across the classloader boundary.
- **Reproducible build.** Fix `project.build.outputTimestamp` in the pom so the shaded JAR's
  SHA-256 is stable for a given source tree and JDK line — this is what makes the manifest pin
  verifiable.
- **ServiceLoader registration.** Ship
  `META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine` naming the implementation class.

## Manifest, sha pin, and the CI/release loop

The plugin is delivered through the connector catalog ([14-connectors.md](./14-connectors.md)):
`connectors/<id>/connector.json` declares a non-RELATIONAL `category` (pick the accurate one:
`DOCUMENT`, `KEY_VALUE`, `WIDE_COLUMN`, `SEARCH`, `GRAPH`), omits the JDBC fields, and pins the
plugin JAR as a `url` driver artifact (`url` / `fileName` / `sha256`). The host downloads,
SHA-256-verifies, and caches it exactly like a JDBC driver (`ACCESSFLOW_DRIVER_CACHE`,
`ACCESSFLOW_DRIVERS_OFFLINE` honoured — pre-seed the cache for air-gapped installs).

Automation discovers plugins by looping `engines/*/` — zero workflow edits per new engine:

- **CI** (`engines` job): installs the backend plain jar, runs `mvn clean verify` for every
  `engines/*/`, then runs [`.github/scripts/check-engine-pins.mjs`](../.github/scripts/check-engine-pins.mjs),
  which compares each built `engines/<id>/target/*-all.jar` against
  `connectors/<id>/connector.json` → `driver.sha256` + `driver.fileName` and **fails on drift**.
- **Release** (`release.yml`): rebuilds every plugin, re-verifies the pins, and publishes all
  `*-all.jar` files to the `gh-pages` branch under `engines/`, where fresh installs download them.

When the engine changes — or a `core.api` type it compiles against changes bytecode — the hash
moves. The re-pin loop, in one PR:

1. Bump `<version>` in `engines/<id>/pom.xml`.
2. `mvn clean package`, then `shasum -a 256 engines/<id>/target/*-all.jar`.
3. Update `url`, `fileName`, `sha256` in `connectors/<id>/connector.json`.

## Test bar

Matching `engines/mongodb/` (the acceptance pattern from AF-414):

- **Unit tests** for the parser, settings parsing, exception translation, result mapping, and
  row-security application — plain JUnit, no containers.
- **Facade-driven Testcontainers IT** — spin up the real database container and drive the full
  `QueryEngine` SPI end to end (parse → execute → RLS/masking → introspection → connection test).
- **Shaded-jar ServiceLoader IT** — load the built `*-all.jar` in an isolated `URLClassLoader` and
  prove the ServiceLoader registration resolves, relocated packages are present, and no host
  classes leak.

## Checklist: adding a new engine

Backend:

1. `engines/<id>/` plugin project implementing `QueryEngine` (conventions + test bar above).
2. `connectors/<id>/connector.json` manifest — non-RELATIONAL `category`, `defaultPort`,
   `defaultSslMode`, pinned `driver` artifact — plus `logo.svg` in the connector folder
   (validated by `.github/scripts/validate-connectors.mjs`).
3. New `DbType` value in `core.api.DbType` + a Flyway migration
   `ALTER TYPE db_type ADD VALUE '<VALUE>'` (with the `executeInTransaction=false` `.conf`, like
   `V71__add_mongodb_db_type.sql`).
4. The compile-enforced exhaustive switches: `core/internal/DefaultJdbcCoordinatesFactory` needs a
   throwing case for the new (non-JDBC) `DbType`. The dispatchers need **nothing** — parser,
   executor, and admin-service routing key off `QueryEngineCatalog.isEngineManaged(dbType)`, which
   reads the manifest category.
5. Engine i18n keys (`error.<engine>.*`) in `messages.properties` + every locale file.
6. Optional per-engine tuning: document the engine's config keys; operators reach them via
   `ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>` with no host code change. Add `application.yml` defaults
   only if the engine warrants documented host-side defaults.

Frontend (registration data only):

7. Add the value to the `DbType` union in `frontend/src/types/api.ts`.
8. Register the editor/results mode in `frontend/src/utils/engineModes.ts` — syntaxes +
   highlighting languages (CQL ≈ `sql`, Redis ≈ `javascript` shell, Query DSL ≈ `json`),
   `canFormat`, `supportsTextToSql`, `defaultResultView` — and a `DB_TYPE_COLOR` entry.
9. `enums.db_type.<VALUE>` label in `frontend/src/locales/en.json` + every locale
   (`locales.parity.test.ts` enforces), and the icon in `frontend/src/components/datasources/DatasourceIcon.tsx`
   + `frontend/public/db-icons/<id>.svg`.

Docs / website (same PR — CLAUDE.md sync rules):

10. Connector row in [14-connectors.md](./14-connectors.md), engine notes in
    [05-backend.md](./05-backend.md), data-model note for the `db_type` enum value in
    [03-data-model.md](./03-data-model.md).
11. Website connector grid (`website/index.html`) and the connectors section of
    `website/docs/index.html`; root `README.md` supported-databases copy.
12. An e2e spec when the engine adds a user-visible flow worth covering (default is "add a spec").

No changes required to: `DefaultQueryEngineCatalog`, `DefaultQueryParser`, `DefaultQueryExecutor`,
`.github/workflows/ci.yml`, or `.github/workflows/release.yml`. `DatasourceAdminServiceImpl` needs a
small `validateDriverChoice` branch **only** when the engine's connection model deviates from the
standard host/port/database/username/password shape — e.g. DynamoDB (cloud credentials + region) and
Neo4j (host/port **or** a `bolt://` / `neo4j+s://` URI override); a standard host/port engine adds
nothing there.
