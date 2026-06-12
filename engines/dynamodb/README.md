# AccessFlow DynamoDB Query Engine

The on-demand Amazon DynamoDB engine plugin (issue [#422](https://github.com/bablsoft/accessflow/issues/422)).
Implements the backend's `core.api.QueryEngine` SPI for **PartiQL** and ships as a **self-contained
shaded JAR** (`accessflow-engine-dynamodb-<version>-all.jar`) bundling the
[AWS SDK for Java v2](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html)
`dynamodb` client over the **url-connection HTTP client (no Netty)**, with `org.reactivestreams`
relocated. The backend resolves it through the connector catalog exactly like a JDBC driver JAR —
download from the URL pinned in
[`connectors/dynamodb/connector.json`](../../connectors/dynamodb/connector.json), SHA-256
verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and discovers the
engine via `java.util.ServiceLoader` (single provider, `engineId()` → `"dynamodb"`).

## Connection model — cloud credentials, not host/port

DynamoDB is the first AccessFlow engine whose "connection" is **cloud credentials + region** rather
than host/port. The existing datasource columns are remapped:

- `database_name` → AWS **region** (required — the SDK signs every request with it, even against a
  custom endpoint).
- `username` → access key id; `password_encrypted` → secret access key (decrypted via
  `CredentialDecryptor` only at client construction, pool-init parity).
- `jdbc_url_override` → optional **custom endpoint** (DynamoDB Local / VPC); blank ⇒ the AWS
  regional endpoint.

`host` / `port` are unused. `DatasourceAdminServiceImpl.validateDriverChoice` enforces this with a
dedicated DynamoDB branch. Session tokens / STS assumed-role credentials are out of scope for v1.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + Testcontainers ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-dynamodb-<version>-all.jar`. The
Testcontainers IT uses the `amazon/dynamodb-local` image.

The shade is **whitelisted** to the AWS SDK + `software.amazon.eventstream` + `org.reactivestreams`
(relocated), and explicitly **excludes** the SDK's default sync/async HTTP clients
(`apache5-client`, `netty-nio-client`) and the host's provided Spring/Netty tree — so the JAR stays
Netty-free and ~7 MB.

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and
`connectors/dynamodb/connector.json` pins it:

- CI (the `engines` job) rebuilds the JAR and **fails on pin drift** (it checks the JAR against
  `connectors/dynamodb/connector.json`, whose folder name matches the `engines/dynamodb/` dir).
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes its bytecode — the
shaded JAR's hash changes. The loop is:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-dynamodb-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in `connectors/dynamodb/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The
DynamoDB specifics:

- The host hands capabilities to `DynamoDbQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages` — the `error.dynamodb.*` keys live in the host's
  `messages.properties`), credential decryption (`CredentialDecryptor`), the tuning config map
  (from `accessflow.proxy.engines.dynamodb.*` via the host's generic `EngineConfigProperties`,
  AF-418: keys `connect-timeout` — default `PT10S` — and `api-call-timeout` — default `PT30S`;
  operators set `ACCESSFLOW_PROXY_ENGINES_DYNAMODB_<KEY>` env vars), and the host UTC clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the
  backend's `core.api` surface plus its own shaded dependencies.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `UnrewritableRowSecurityException`, `QueryExecutionFailedException`,
  `QueryExecutionTimeoutException`, `DatasourceConnectionTestException`).

## PartiQL semantics

- **Classification** — a submission is either a single PartiQL statement or a JSON table-management
  command (it begins with `{`). `SELECT` → SELECT; `INSERT` → INSERT; `UPDATE` → UPDATE; `DELETE` →
  DELETE; a `{"CreateTable"|"DeleteTable"|"UpdateTable": {…}}` command → DDL (common fields mapped
  to the typed control-plane request). Exactly one statement per submission; transaction/batch verbs
  (`EXECUTE TRANSACTION`, `BEGIN`) are rejected with HTTP 422.
- **Tables and grants** — `referencedTables` carries the (case-preserved) table name; an index
  access (`"Table"."Index"`) resolves to its base table, feeding the host's schema allow-list and
  permission grants unchanged.
- **Row-level security** — predicates are ANDed into the PartiQL WHERE clause, values bound as
  **positional `?` parameters** (in source order), never concatenated. Unlike CQL, DynamoDB can
  filter on **any** attribute (a non-key predicate becomes a server-side Scan filter), supporting
  `=, <>, <, <=, >, >=, IN, NOT IN`. An empty value list is a **fail-closed deny-all** — the
  executor returns an empty result without touching DynamoDB. INSERT into a policied table is
  rejected outright (it has no WHERE clause).
- **Masking** — post-fetch via the shared `core.api.ColumnMasker`, applied **recursively by
  dot-path**: a mask on `profile.ssn` redacts the nested leaf while siblings stay visible; a bare
  attribute mask recurses into nested maps/lists.
- **Execution** — PartiQL runs through `ExecuteStatement`; SELECTs page through `NextToken` capped
  at `maxRows + 1` (truncation detection). DML returns 1 affected row (0 on deny-all); a JSON DDL
  command runs the control-plane call and returns 0. The host-computed statement timeout is applied
  per request via `apiCallTimeout`.
- **Connection & introspection** — one `DynamoDbClient` is cached per datasource and dropped on
  `evictDatasource`. `testConnection` issues `ListTables` (limit 1). Schema introspection lists
  tables, reads each table's key schema via `DescribeTable` (partition/sort key flagged PK), and
  samples items via a bounded `Scan` for the remaining attribute names/types.
