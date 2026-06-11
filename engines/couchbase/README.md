# AccessFlow Couchbase Query Engine

The on-demand Couchbase engine plugin (issue [#412](https://github.com/bablsoft/accessflow/issues/412)).
Implements the backend's `core.api.QueryEngine` SPI for **SQL++ (N1QL)** and ships as a
**self-contained shaded JAR** (`accessflow-engine-couchbase-<version>-all.jar`) bundling the
Couchbase Java SDK (`java-client` + `core-io`) with a relocated Reactor and Jackson. The backend
resolves it through the connector catalog exactly like a JDBC driver JAR — download from the URL
pinned in [`connectors/couchbase/connector.json`](../../connectors/couchbase/connector.json),
SHA-256 verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and
discovers the `CouchbaseQueryEngine` via `java.util.ServiceLoader`.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + Testcontainers ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-couchbase-<version>-all.jar`. The
Testcontainers IT uses the `couchbase/server` **community** image (KV + Query + Index services),
which is large (~1.5 GB) and takes a minute or two to boot — expect the first local run to be slow.

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and the
connector manifest pins it:

- `connectors/couchbase/connector.json` → `driver.url` / `driver.fileName` / `driver.sha256`.
- CI (the `engines` job) rebuilds the JAR and **fails on pin drift**.
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes its bytecode — the
shaded JAR's hash changes. The loop is:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-couchbase-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in `connectors/couchbase/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The
Couchbase specifics:

- The host hands capabilities to `CouchbaseQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages`, backed by the host `MessageSource` — the `error.couchbase.*` keys
  live in the host's `messages.properties`), credential decryption (`CredentialDecryptor`), the
  tuning config map (from `accessflow.proxy.engines.couchbase.*` via the host's generic
  `EngineConfigProperties`, AF-418: keys `connect-timeout`, `wait-until-ready-timeout` — ISO-8601
  durations, default `PT10S` — and `scan-consistency` — `request-plus` (default; reads observe
  mutations submitted before the query) or `not-bounded` (Couchbase's own default, faster);
  operators set `ACCESSFLOW_PROXY_ENGINES_COUCHBASE_<KEY>` env vars), and the host UTC clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the
  backend's `core.api` surface plus its own shaded dependencies.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `UnrewritableRowSecurityException`, `QueryExecutionFailedException`,
  `QueryExecutionTimeoutException`, `DatasourceConnectionTestException`).

## SQL++ semantics

- **Classification** — `SELECT` → SELECT; `INSERT`/`UPSERT` → INSERT; `UPDATE`/`MERGE` → UPDATE;
  `DELETE` → DELETE; `CREATE`/`DROP` of `[PRIMARY] INDEX` / `SCOPE` / `COLLECTION` → DDL. Exactly
  one statement per submission. Anything else — including `CURL()` (server-side exfiltration),
  JavaScript UDF statements (`CREATE`/`EXECUTE`/`DROP FUNCTION`), and `system:*` keyspaces — is
  rejected with HTTP 422.
- **Keyspaces and grants** — every statement runs through the datasource bucket's **default
  scope** query context: a bare `FROM users` resolves to `<bucket>._default.users` and carries
  `users` in `referencedTables` (matching a collection-level grant); a fully-qualified
  `bucket.scope.collection` path is carried verbatim (matching an exact-path grant or an
  `allowedSchemas` entry on the bucket segment).
- **Row-level security** — predicates are ANDed into the WHERE clause of simple single-keyspace
  SELECT / UPDATE / DELETE statements, values bound as **named parameters** (`$af_rls_n`), never
  concatenated. INSERT/UPSERT into a policied keyspace, MERGE, and any unprovable shape (CTE,
  subquery, JOIN/NEST/UNNEST, USE KEYS, set operations, multiple keyspaces) fail closed with 422.
- **Masking** — post-fetch via the shared `core.api.ColumnMasker`, `collection.field` → bare
  `field` precedence, applied to the flattened result page (a `SELECT *` page is unwrapped from
  its keyspace-alias wrapper first so field-level refs match).
- **Connection** — `couchbase://`/`couchbases://` assembled from host/port + `SslMode`
  (TLS bootstraps on port 11207; plain KV on 11210), or the datasource URL override taken
  verbatim. The bucket is the datasource's `database_name`. One `Cluster` is cached per
  datasource and dropped on `evictDatasource`.
