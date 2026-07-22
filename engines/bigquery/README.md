# AccessFlow BigQuery Query Engine

The on-demand Google BigQuery engine plugin (issue AF-629). Implements the backend's
`core.api.QueryEngine` SPI for **GoogleSQL** and ships as a **self-contained shaded JAR**
(`accessflow-engine-bigquery-<version>-all.jar`) bundling the native
[google-cloud-bigquery](https://cloud.google.com/java/docs/reference/google-cloud-bigquery/latest/overview)
HTTP/JSON client with **every third-party package relocated** under
`com.bablsoft.accessflow.engine.bigquery.shaded.*`. The backend resolves it through the connector
catalog exactly like a JDBC driver JAR — download from the URL pinned in
[`connectors/bigquery/connector.json`](../../connectors/bigquery/connector.json), SHA-256
verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and discovers the
engine via `java.util.ServiceLoader` (single provider, `engineId()` → `"bigquery"`).

## Connection model — cloud credentials + `project[.dataset]`, not host/port

BigQuery's "connection" is **cloud credentials plus a project**, so the existing datasource columns
are remapped:

- `database_name` → **`project`** or **`project.dataset`**. The single optional dot splits a GCP
  project id from a default dataset; when the dataset is present, unqualified table names in
  queries resolve against it (`setDefaultDataset`) and schema introspection is scoped to it.
- `password_encrypted` → the **service-account key JSON** (decrypted via `CredentialDecryptor`
  only at client construction, pool-init parity). Invalid key JSON surfaces as
  `error.bigquery.invalid_credentials`.
- `jdbc_url_override` → optional **custom endpoint** (the BigQuery emulator); when set, the client
  authenticates with `NoCredentials` instead of a service account.

`host` / `port` / `username` are unused.

## Client cache

One `BigQuery` client is cached per datasource (`BigQueryClientManager`). The client itself is a
cheap thread-safe HTTP stub, but building one parses and validates the service-account key JSON —
caching avoids re-doing that per query. `BigQuery` holds no closeable connection state (each call
is an independent HTTP request), so `evictDatasource` simply drops the map entry.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + Testcontainers ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-bigquery-<version>-all.jar`. The
Testcontainers IT uses the [`ghcr.io/goccy/bigquery-emulator`](https://github.com/goccy/bigquery-emulator)
image (`--project=test --dataset=dataset1`, REST port 9050) and drives the full SPI — parse,
execute, row security, masking, sampling, introspection, probe, eviction — through the emulator
endpoint + `NoCredentials` path. One emulator caveat: it never reports `numDmlAffectedRows`
(real BigQuery does, through the same `QueryStatistics` path), so the IT verifies DML by reading
its effect back.

### Relocation strategy

The engine only uses BigQuery's plain **HTTP/JSON** query path (`google-api-services-bigquery`
over `google-http-client`), never the Storage Read API — so the entire gRPC lane the client drags
along for it is **excluded at the dependency level**: `google-cloud-bigquerystorage`, `gax-grpc`,
the `io.grpc` runtime, `grpc-netty-shaded`, Apache Arrow + Netty buffers, conscrypt, and the
Jackson/Apache-HTTP stacks that only serve those paths. What remains is whitelisted into the shade
and **fully relocated** under the plugin namespace: the `com.google.*` stack (bigquery client,
discovery model, http client, auth, gax, guava, gson, protobuf), `io.opencensus`,
`io.opentelemetry`, the tiny pure-Java `io.grpc` context (opencensus needs `io.grpc.Context`),
`org.threeten`, `org.json`, and `commons-codec`. Annotation-only jars (jsr305, error-prone,
checkerframework, jspecify, …) are not bundled at all. `ShadedJarServiceLoaderIT` asserts the
registration, the relocations, and that no unrelocated third-party / host / Spring / Netty class
leaks. The JAR stays ~15 MB.

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and
`connectors/bigquery/connector.json` pins it:

- CI (the `engines` job) rebuilds the JAR and **fails on pin drift** (it checks the JAR against
  `connectors/bigquery/connector.json`, whose folder name matches the `engines/bigquery/` dir).
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes its bytecode — the
shaded JAR's hash changes. The loop is:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-bigquery-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in `connectors/bigquery/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The
BigQuery specifics:

- The host hands capabilities to `BigQueryQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages` — the `error.bigquery.*` keys live in the host's
  `messages.properties`), credential decryption (`CredentialDecryptor`), the tuning config map
  (from `accessflow.proxy.engines.bigquery.*` via the host's generic `EngineConfigProperties`,
  AF-418: keys `connect-timeout` — default `PT10S` — and `read-timeout` — default `PT30S`;
  operators set `ACCESSFLOW_PROXY_ENGINES_BIGQUERY_<KEY>` env vars), and the host UTC clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the
  backend's `core.api` surface plus its own shaded dependencies.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `UnrewritableRowSecurityException`, `QueryExecutionFailedException`,
  `QueryExecutionTimeoutException`, `DatasourceConnectionTestException`).

## GoogleSQL semantics

- **Classification** — a keyword classifier over a GoogleSQL-aware token stream (triple-quoted /
  raw / bytes strings, backtick identifiers spanning whole paths, `#`/`--`/block comments).
  `SELECT` → SELECT (a `WITH` prologue classifies by the final top-level verb); `INSERT` → INSERT;
  `UPDATE` → UPDATE; `DELETE` → DELETE; `MERGE` → UPDATE; `TRUNCATE TABLE` and
  `CREATE`/`ALTER`/`DROP` of TABLE / VIEW / MATERIALIZED VIEW / SCHEMA → DDL. Everything else
  fails closed with HTTP 422 (`error.bigquery.unsupported_statement`): scripting and procedural
  verbs (`BEGIN`, `DECLARE`, `CALL`, `EXECUTE IMMEDIATE`, `IF`, `LOOP`, `WHILE`, `RAISE`,
  `RETURN`), admin verbs (`EXPORT`, `LOAD`, `ASSERT`, `GRANT`, `REVOKE`, `SET`), and
  routine / row-access-policy / reservation / index / model DDL. Exactly one statement per
  submission; user-supplied `?` / `@param` placeholders are rejected (the positional binds are
  reserved for row security, and BigQuery forbids mixing parameter styles).
- **Tables and grants** — `referencedTables` carries lowercase dot-joined paths
  (`project.dataset.table`, backticks stripped — including the single-backtick-spanning form),
  collected from `FROM`/`JOIN`/`USING`/targets at every depth with CTE aliases excluded, feeding
  the host's schema allow-list and permission grants unchanged.
- **Row-level security** — predicates are ANDed into the WHERE clause (spliced before
  GROUP BY / HAVING / QUALIFY / WINDOW / ORDER BY / LIMIT / OFFSET), values bound as **positional
  `?` parameters**, never concatenated; matching is by the table path's last segment, lowercased.
  Supported operators: `=, <>, <, <=, >, >=, IN, NOT IN, IS NULL`. The rewrite **fails closed**
  (`error.row_security_bigquery_unrewritable`) on CTEs, subqueries anywhere, JOINs, comma-joins,
  set operations, MERGE, and multi-table statements; INSERT into a policied table is rejected
  outright (`error.row_security_bigquery_insert_unsupported`). An empty value list is a
  **fail-closed deny-all** — the executor returns an empty result without touching BigQuery.
- **Masking** — post-fetch via the shared `core.api.ColumnMasker`, applied **recursively by
  dot-path**: a mask on `profile.ssn` redacts the nested STRUCT leaf while siblings stay visible;
  a bare column mask recurses into nested records/arrays (FULL collapses them to the mask token).
- **Execution** — statements run as BigQuery **query jobs** pinned to the default dataset, with
  the host-computed statement timeout enforced twice: server-side via `jobTimeoutMs` and
  client-side by polling the job against the engine clock (cancel + timeout exception on expiry).
  SELECTs page at `maxRows + 1` for truncation detection; DML (incl. MERGE) reports the job's
  `numDmlAffectedRows`; DDL returns 0 affected rows. TIMESTAMP values map to `java.time.Instant`,
  RECORDs to ordered maps, REPEATED fields to lists.
- **Connection & introspection** — `testConnection` lists the project's datasets capped at one
  page entry (no billable query job). Schema introspection maps datasets → schemas and tables →
  tables (only the pinned default dataset when `database_name` carries one), flattening RECORD
  fields into dot-path columns with `ARRAY<…>` noting REPEATED mode; BigQuery has no primary
  keys, so no PK flags.
