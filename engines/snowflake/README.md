# AccessFlow Snowflake Query Engine

The on-demand Snowflake engine plugin (issue AF-629). Implements the backend's
`core.api.QueryEngine` SPI for **Snowflake SQL** and ships as a **self-contained shaded JAR**
(`accessflow-engine-snowflake-<version>-all.jar`) bundling the
[Snowflake JDBC driver](https://docs.snowflake.com/en/developer-guide/jdbc/jdbc). The backend
resolves it through the connector catalog exactly like a JDBC driver JAR — download from the URL
pinned in [`connectors/snowflake/connector.json`](../../connectors/snowflake/connector.json),
SHA-256 verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and
discovers the engine via `java.util.ServiceLoader` (single provider, `engineId()` → `"snowflake"`).

Snowflake is JDBC-accessed but deliberately **engine-managed, not host-pooled JDBC**: the plugin
owns the classifier, row security, masking, and the connection lifecycle (see below), keeping the
warehouse dialect's semantics — `QUALIFY`, `MERGE`, `$$…$$` bodies, stages, tasks — out of the
host's JSqlParser path.

## Connection model — account host, short-lived sessions

- `host` → the **account host** (`<account>.snowflakecomputing.com`); the URL is
  `jdbc:snowflake://<host>`.
- `database_name` → the Snowflake **database** (the `db` connection property).
- `username` → user; `password_encrypted` → **password _or_ key-pair credential**: when the
  decrypted value starts with `-----BEGIN PRIVATE KEY-----` it is parsed as an unencrypted PKCS#8
  PEM (RSA first, EC fallback) and passed as the object-valued `privateKey` property.
  Passphrase-protected PEMs (`-----BEGIN ENCRYPTED PRIVATE KEY-----`) are rejected
  (`error.snowflake.encrypted_private_key_unsupported`) — AccessFlow already encrypts the stored
  credential at rest, so store the decrypted PKCS#8 form.
- `jdbc_url_override` → optional **full `jdbc:snowflake://` URL** used verbatim, the place to pin
  `warehouse=…&role=…&schema=…` parameters. Any other scheme is rejected
  (`error.snowflake.invalid_url_override`).
- The driver is instantiated directly (`new SnowflakeDriver()`), never via `DriverManager` — the
  plugin's isolated classloader is invisible to `DriverManager`'s caller checks.

**Per-request connections, no pool, no cache.** Every execute / probe / introspection opens a
fresh connection and closes it (try-with-resources); `evictDatasource` and `shutdown` are no-ops.
Rationale: a warehouse is billed while resumed and an idle pooled session would keep it (and its
session state) alive; AccessFlow's governed traffic is sparse, human-paced, and approval-gated, so
connection setup cost is noise; and a fresh session can never leak `USE`-style state between
requests.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + shaded-jar IT + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-snowflake-<version>-all.jar`.

The shade is **whitelisted** to `net.snowflake:snowflake-jdbc` only, with **no relocations**: the
Snowflake driver is itself a self-contained fat jar that already relocates its third-party
dependencies under `net/snowflake/client/jdbc/internal/*`, so nothing can collide across the
classloader boundary. The whitelist also keeps the host's provided Spring tree out of the JAR.

**No live-database IT.** There is no free Snowflake emulator (LocalStack's Snowflake emulation is
a Pro feature), so the test suite is: exhaustive unit tests for the tokenizer / classifier /
row-security applier / result mapper / settings / key parser / exception translator, executor and
probe/introspector tests against mocked JDBC connections, and the shaded-jar `ServiceLoader` IT
(isolated classloader chain, registration, no host-class leakage).

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and
`connectors/snowflake/connector.json` pins it:

- CI (the `engines` job) rebuilds the JAR and **fails on pin drift**.
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes its bytecode — the
shaded JAR's hash changes. The loop is:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-snowflake-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in `connectors/snowflake/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The
Snowflake specifics:

- The host hands capabilities to `SnowflakeQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages` — the `error.snowflake.*` keys live in the host's
  `messages.properties`), credential decryption (`CredentialDecryptor`), the tuning config map
  (from `accessflow.proxy.engines.snowflake.*` via the host's generic `EngineConfigProperties`,
  AF-418: keys `login-timeout` — default `PT30S` — and `network-timeout` — default `PT60S`;
  operators set `ACCESSFLOW_PROXY_ENGINES_SNOWFLAKE_<KEY>` env vars), and the host UTC clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the
  backend's `core.api` surface plus its own shaded dependencies.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `UnrewritableRowSecurityException`, `QueryExecutionFailedException`,
  `QueryExecutionTimeoutException`, `DatasourceConnectionTestException`).

## Snowflake SQL semantics

- **Classification** — a keyword classifier over a Snowflake-aware tokenizer (comments, `''` and
  backslash string escapes, `"quoted identifiers"`, opaque `$$…$$` blocks). Exactly one statement
  per submission (single trailing `;` allowed). `SELECT` (incl. a bare parenthesized select) →
  SELECT; `INSERT` → INSERT; `UPDATE` → UPDATE; `DELETE` → DELETE; `MERGE` → UPDATE; `TRUNCATE`
  and `CREATE`/`ALTER`/`DROP` of TABLE / VIEW / SCHEMA / DATABASE (incl. `OR REPLACE`,
  `MATERIALIZED`, `TRANSIENT`/`TEMPORARY`/`TEMP`) → DDL. A leading `WITH` is classified by the
  final top-level verb. Everything else is rejected with HTTP 422 — scripting / session /
  data-movement verbs (`CALL`, `EXECUTE`, `BEGIN`, `DECLARE`, `PUT`, `GET`, `COPY`, `UNLOAD`,
  `USE`, `SHOW`, `DESCRIBE`, `GRANT`, `REVOKE`, `UNDROP`, `COMMENT`, `SET`, `UNSET`, `LIST`,
  `REMOVE`) and `CREATE`/`ALTER`/`DROP` of PROCEDURE / FUNCTION / TASK / STREAM / PIPE / STAGE /
  WAREHOUSE / USER / ROLE / INTEGRATION / SHARE. A user-supplied `?` placeholder is rejected up
  front (`error.snowflake.placeholders_forbidden`) — positional binds are reserved for the
  row-security splice.
- **Tables and grants** — `referencedTables` carries every table read or written (subselects
  included, CTE names excluded, `TABLE(…)`/`LATERAL`/`VALUES` function refs skipped), normalized
  to lowercase dot-joined form, feeding the host's schema allow-list and permission grants
  unchanged.
- **Row-level security** — predicates are ANDed into the WHERE clause (inserted before
  `GROUP BY` / `HAVING` / `QUALIFY` / `ORDER BY` / `LIMIT` / `OFFSET` / `FETCH` when absent),
  values bound as **positional `?` parameters**, never concatenated. Operators: `=, <>, <, <=, >,
  >=, IN, NOT IN, IS NULL`. Only provably filterable shapes are rewritten — a simple single-table
  SELECT / UPDATE / DELETE; a CTE, any subquery, any JOIN or comma-join, a set operation
  (`UNION`/`INTERSECT`/`EXCEPT`/`MINUS`), a MERGE, or a multi-table statement over a policied
  table **fails closed** with HTTP 422 (plain `QUALIFY` is fine; one with a subquery is caught by
  the subquery check). An empty value list is a **fail-closed deny-all** — the executor returns an
  empty result (0 affected rows for writes) without touching Snowflake. INSERT into a policied
  table is rejected outright; DDL is unaffected.
- **Masking** — post-fetch via the shared `core.api.ColumnMasker`; a directive's `columnRef`
  matches when its last dot-segment equals the result column label case-insensitively (Snowflake
  folds unquoted identifiers to uppercase), restricted columns without a directive mask FULL, and
  NULL cells pass through.
- **Execution** — one `PreparedStatement` per request with the host-computed statement timeout
  (`setQueryTimeout`, min 1 s); reads cap the driver at `maxRows + 1` for truncation detection.
  Driver `SQLTimeoutException`s, vendor code 604 (statement canceled), and SQLSTATE `57014` map to
  the timeout exception; other failures carry the driver message, SQLSTATE, and vendor code
  verbatim. Table samples (AF-443) run `SELECT * FROM "schema"."table"` through the same governed
  pipeline.
- **Connection test & introspection** — `SELECT 1` probe; `DatabaseMetaData`-driven introspection
  (tables + views of every schema in the database except `INFORMATION_SCHEMA`, columns with
  type/nullability, `getPrimaryKeys` flags).

## i18n keys

`error.snowflake.blank`, `error.snowflake.multiple_statements`,
`error.snowflake.unsupported_statement` (`{0}` = verb), `error.snowflake.unbalanced`,
`error.snowflake.table_required`, `error.snowflake.placeholders_forbidden`,
`error.snowflake.invalid_private_key`, `error.snowflake.encrypted_private_key_unsupported`,
`error.snowflake.invalid_url_override` (`{0}` = the rejected URL),
`error.row_security_snowflake_unrewritable` (`{0}` = table),
`error.row_security_snowflake_insert_unsupported` (`{0}` = table) — all defined in the host's
`messages.properties` (+ every locale file, `MessagesParityTest`).
