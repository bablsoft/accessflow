# AccessFlow Databricks SQL Query Engine

The on-demand Databricks SQL warehouse engine plugin (issue AF-629, the first `WAREHOUSE` engine).
Implements the backend's `core.api.QueryEngine` SPI for **Databricks SQL** (the Spark SQL family)
and ships as a **self-contained shaded JAR** (`accessflow-engine-databricks-<version>-all.jar`).
The backend resolves it through the connector catalog exactly like a JDBC driver JAR — download
from the URL pinned in [`connectors/databricks/connector.json`](../../connectors/databricks/connector.json),
SHA-256 verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and
discovers the engine via `java.util.ServiceLoader` (single provider, `engineId()` → `"databricks"`).

## No vendor SDK, no JDBC driver

The engine talks directly to the [Databricks SQL Statement Execution API](https://docs.databricks.com/api/workspace/statementexecution)
with the JDK `java.net.http.HttpClient`. The Databricks JDBC driver and Java SDK each weigh tens of
megabytes and drag large dependency trees; the Statement Execution API needs nothing beyond HTTPS +
JSON, so the plugin's **only runtime dependency is Jackson** (databind + core + annotations),
relocated to `com.bablsoft.accessflow.engine.databricks.shaded.com.fasterxml.jackson` so it can
never clash with the host's Jackson across the classloader boundary. The shaded JAR stays ~2–3 MB.

## Connection model — workspace host + warehouse path + PAT

- `host` → the **workspace host** (`adb-….azuredatabricks.net`, `dbc-….cloud.databricks.com`);
  the API base URL is `https://<host>`.
- `jdbc_url_override` → **required**: the SQL warehouse's HTTP path, either the bare
  `/sql/1.0/warehouses/<id>` form (last path segment = `warehouse_id`) or a full
  `http(s)://host/sql/1.0/warehouses/<id>` URL, in which case the URL's scheme + authority become
  the base URL (this form is also the stub-server test hook). Anything malformed is rejected with
  `error.databricks.warehouse_path_invalid`.
- `username` is unused for auth; `password_encrypted` → the **personal access token**, decrypted
  via `CredentialDecryptor` only at request-build time and sent as `Authorization: Bearer <PAT>`.
- `database_name` → optional **Unity Catalog catalog**, submitted as the statement's `catalog`
  field and used to qualify introspection queries. Blank ⇒ the warehouse's default catalog.

## Statement Execution API mapping

- **Submit** — `POST /api/2.0/sql/statements` with `wait_timeout` (default `10s`, clamped to the
  API's allowed 5–50 s), `on_wait_timeout=CONTINUE` (hybrid wait: short statements return inline,
  long ones return `PENDING`), `format=JSON_ARRAY`, `disposition=INLINE`, and — for SELECTs —
  `row_limit = maxRows + 1` (the truncation sentinel).
- **Poll** — while the state is `PENDING`/`RUNNING`, `GET /api/2.0/sql/statements/{id}` at the
  configured `poll-interval`; the deadline is the host-computed statement timeout measured with the
  host clock.
- **Cancel** — on deadline expiry the engine best-effort
  `POST /api/2.0/sql/statements/{id}/cancel`s the statement, then raises
  `QueryExecutionTimeoutException`.
- **Results** — `INLINE`-only in v1: `result.data_array` pages are followed through
  `next_chunk_index` via `GET …/result/chunks/{n}`. The `EXTERNAL_LINKS` disposition (presigned
  cloud-storage URLs for very large results) is **out of scope for v1** — the proxy's row cap keeps
  governed results well inside the inline limit.
- **Errors** — a terminal `FAILED`/`CANCELED`/`CLOSED` state and non-2xx HTTP responses (401/403,
  429, 5xx) surface the verbatim API `message` as the `QueryExecutionFailedException` detail. No
  retry loops in v1.
- **DML affected rows** — Databricks returns DML results as a one-row result set with a
  `num_affected_rows` column; the executor parses it when present and reports **0** when the shape
  is absent (older channel versions / DDL), preferring a conservative count over guessing. DDL
  always reports 0.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + stub-server ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-databricks-<version>-all.jar`. The
integration tests need **no containers and no Databricks account**: the full SPI is driven against
an in-process `com.sun.net.httpserver.HttpServer` stub of the Statement Execution API
(submit/poll/cancel/chunks), which is what the full-URL `jdbc_url_override` form exists for.

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and
`connectors/databricks/connector.json` pins it:

- CI (the `engines` job) rebuilds the JAR and **fails on pin drift**.
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes bytecode — the loop:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-databricks-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in `connectors/databricks/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The
Databricks specifics:

- The host hands capabilities to `DatabricksQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages` — the `error.databricks.*` keys live in the host's
  `messages.properties`), credential decryption (`CredentialDecryptor`), the tuning config map
  (from `accessflow.proxy.engines.databricks.*`, AF-418's generic lane: `connect-timeout` — default
  `PT10S` — the HttpClient connect timeout; `wait-timeout` — default `PT10S`, clamped to 5–50 s —
  the API's server-side hybrid wait; `poll-interval` — default `PT1S` — the client-side status-poll
  cadence; operators set `ACCESSFLOW_PROXY_ENGINES_DATABRICKS_<KEY>` env vars), and the host UTC
  clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the
  backend's `core.api` surface plus its own shaded (relocated) Jackson.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `UnrewritableRowSecurityException`, `QueryExecutionFailedException`,
  `QueryExecutionTimeoutException`, `DatasourceConnectionTestException`).
- The engine holds **no per-datasource state** (every call is a stateless HTTPS request), so
  `evictDatasource` and `shutdown` are no-ops.

## Databricks SQL semantics

- **Classification** — exactly one statement per submission; a keyword classifier over a
  comment/string/backtick-aware tokenizer (the Couchbase/Cassandra pattern). `SELECT` → SELECT;
  `INSERT [INTO|OVERWRITE]` → INSERT; `UPDATE` → UPDATE; `DELETE` → DELETE; `MERGE` → UPDATE;
  `TRUNCATE` and `CREATE`/`ALTER`/`DROP` of `TABLE`/`VIEW`/`SCHEMA`/`DATABASE`/`MATERIALIZED VIEW`
  (incl. `OR REPLACE`) → DDL. A leading `WITH` classifies by the final top-level verb.
  Session/state, ingestion, maintenance, and governance verbs (`USE`, `SET`, `CACHE`, `COPY`,
  `CALL`, `GRANT`, `REVOKE`, `OPTIMIZE`, `VACUUM`, `SHOW`, `DESCRIBE`, `EXPLAIN`, …) and DDL over
  functions / volumes / shares / catalogs / credentials / principals are rejected with HTTP 422;
  anything off-list fails closed the same way. User SQL containing a `?` positional or `:name`
  named parameter marker is rejected (`error.databricks.parameter_marker_forbidden`) — the `::`
  cast shorthand and `:` inside strings/backticks are fine — because named parameters are reserved
  for row security.
- **Tables and grants** — `referencedTables` carries every table after
  FROM / JOIN / INTO / OVERWRITE / UPDATE / MERGE USING / TRUNCATE / the DDL object name,
  normalized (backticks stripped, lowercased, dot-joined; `catalog.schema.table` keeps its
  segments), descending into subselects while skipping CTE names, aliases, `VALUES`, and
  `LATERAL VIEW` — feeding the host's allow-list and permission grants unchanged.
- **Row-level security** — predicates are ANDed into the WHERE clause (spliced before
  `GROUP BY`/`HAVING`/`QUALIFY`/`ORDER`/`SORT`/`CLUSTER`/`DISTRIBUTE BY`/`LIMIT`/`OFFSET` when no
  WHERE exists), values bound as **typed named parameters** `:afp_1 … :afp_n`
  (BOOLEAN/BIGINT/DOUBLE/STRING), never concatenated. Directives match by last-segment table name.
  Supported operators: `=, <>, <, <=, >, >=, IN, NOT IN, IS NULL`. The rewrite **fails closed**
  (HTTP 422) on CTEs, subqueries, JOINs, comma-joins, set operations, `MERGE`, `LATERAL VIEW`,
  more than one distinct table, or user SQL already containing the literal `:afp_` text; INSERT
  into a policied table is rejected outright; an empty value list is a **fail-closed deny-all** —
  the executor returns an empty result with **zero HTTP calls**. DDL is unaffected (it is governed
  by the DDL permission + approval path).
- **Masking** — post-fetch via the shared `core.api.ColumnMasker`, flat case-insensitive
  column-name matching (Databricks result columns are flat; a qualified ref matches by its last
  segment). Restricted columns without a directive collapse to FULL.
- **Sampling** — `sampleTable` issues `SELECT * FROM \`schema\`.\`table\`` through the same
  governed parse → row-security → masking pipeline.
- **Connection test & introspection** — `testConnection` runs `SELECT 1` through the same client.
  Introspection queries `information_schema.tables` / `.columns` (catalog-qualified when the
  datasource pins one), grouped by `table_schema` into the engine-neutral `DatabaseSchemaView`.
  No primary-key flags in v1.
