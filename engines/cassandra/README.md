# AccessFlow Cassandra Query Engine

The on-demand Cassandra / ScyllaDB engine plugin (issue [#421](https://github.com/bablsoft/accessflow/issues/421)).
Implements the backend's `core.api.QueryEngine` SPI for **CQL** and ships as a **self-contained
shaded JAR** (`accessflow-engine-cassandra-<version>-all.jar`) bundling the
[DataStax Java driver](https://github.com/apache/cassandra-java-driver) with a relocated Netty,
Typesafe Config and HdrHistogram. The backend resolves it through the connector catalog exactly
like a JDBC driver JAR — download from the URL pinned in
[`connectors/cassandra/connector.json`](../../connectors/cassandra/connector.json), SHA-256
verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and discovers the
engine via `java.util.ServiceLoader`.

## One JAR, two connectors (Cassandra + ScyllaDB)

ScyllaDB speaks the identical CQL binary protocol, so this single JAR registers **two**
`QueryEngine` providers in `META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine`:
`CassandraQueryEngine` (`engineId()` → `"cassandra"`) and the thin `ScyllaDbQueryEngine extends
CassandraQueryEngine` (`engineId()` → `"scylladb"`). The host matches `connectorId == engineId()`
when ServiceLoading, so the same JAR (pinned by both
[`connectors/cassandra/connector.json`](../../connectors/cassandra/connector.json) and
[`connectors/scylladb/connector.json`](../../connectors/scylladb/connector.json)) backs both
connectors. A separate `DbType.SCYLLADB` exists only because the connector catalog allows one
connector per non-CUSTOM dialect — behaviour is byte-for-byte identical.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + Testcontainers ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-cassandra-<version>-all.jar`. The
Testcontainers IT uses the `cassandra:5` image, which takes a minute or two to boot — expect the
first local run to be slow.

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and both
connector manifests pin it:

- `connectors/cassandra/connector.json` **and** `connectors/scylladb/connector.json` →
  identical `driver.url` / `driver.fileName` / `driver.sha256` (one JAR).
- CI (the `engines` job) rebuilds the JAR and **fails on pin drift** (it checks the JAR against
  `connectors/cassandra/connector.json`, whose folder name matches the `engines/cassandra/` dir).
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes its bytecode — the
shaded JAR's hash changes. The loop is:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-cassandra-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in **both** `connectors/cassandra/connector.json` and
   `connectors/scylladb/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The
Cassandra specifics:

- The host hands capabilities to `CassandraQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages` — the `error.cassandra.*` keys live in the host's
  `messages.properties`), credential decryption (`CredentialDecryptor`), the tuning config map
  (from `accessflow.proxy.engines.cassandra.*` / `…scylladb.*` via the host's generic
  `EngineConfigProperties`, AF-418: keys `connect-timeout` and `request-timeout` — ISO-8601
  durations, default `PT10S`; operators set `ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>` env vars), and
  the host UTC clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the
  backend's `core.api` surface plus its own shaded dependencies.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `UnrewritableRowSecurityException`, `QueryExecutionFailedException`,
  `QueryExecutionTimeoutException`, `DatasourceConnectionTestException`).

## CQL semantics

- **Classification** — `SELECT` → SELECT; `INSERT` → INSERT; `UPDATE` → UPDATE; `DELETE` → DELETE
  (lightweight transactions — `IF NOT EXISTS` / `IF …` — ride on their base type);
  `CREATE`/`ALTER`/`DROP` of a `TABLE` / `KEYSPACE` / `INDEX` / `TYPE` / `MATERIALIZED VIEW` and
  `TRUNCATE` → DDL. Exactly one statement per submission. `BEGIN … BATCH` (the multi-statement
  carrier) and `CREATE`/`DROP FUNCTION`/`AGGREGATE` (server-side code) are rejected with distinct
  HTTP 422 messages.
- **Tables and grants** — `referencedTables` carries every referenced table as a bare `table`
  (resolved against the datasource keyspace) or a qualified `keyspace.table`, feeding the host's
  schema allow-list and permission grants unchanged.
- **Row-level security** — predicates are ANDed into the WHERE clause, values bound as **named
  parameters** (`:af_rls_n`), never concatenated. CQL can only filter on key columns, so a
  predicate is spliced **only** when its column is a partition/clustering key of the target table
  **and** its operator is one of `=, IN, <, <=, >, >=`; a non-key column, an unsupported operator
  (`!=` / `NOT IN` don't exist in CQL WHERE), or a deny-all value list **fails closed** with 422
  rather than injecting `ALLOW FILTERING`. INSERT into a policied table is rejected outright.
- **Masking** — post-fetch via the shared `core.api.ColumnMasker`, `table.column` → bare `column`
  precedence, applied to the flat result page.
- **Connection** — contact point from host/port (default 9042), the required per-datasource
  `local_datacenter` (the driver's load-balancing datacenter), the datasource keyspace as the
  session's default keyspace, auth from username + decrypted password, and SSL when
  `ssl_mode != DISABLE`. One `CqlSession` is cached per datasource and dropped on
  `evictDatasource`. Schema introspection reads the driver's cluster metadata (non-system
  keyspaces), flagging partition/clustering columns as the primary key.
