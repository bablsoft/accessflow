# AccessFlow Redis Query Engine

The on-demand Redis engine plugin (issue [#419](https://github.com/bablsoft/accessflow/issues/419)).
Implements the backend's `core.api.QueryEngine` SPI for governed **key-value** access and ships as a
**self-contained shaded JAR** (`accessflow-engine-redis-<version>-all.jar`) bundling the
[Jedis](https://github.com/redis/jedis) driver with a relocated commons-pool2 / gson / org.json. The
backend resolves it through the connector catalog exactly like a JDBC driver JAR — download from the
URL pinned in [`connectors/redis/connector.json`](../../connectors/redis/connector.json), SHA-256
verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and discovers the
`RedisQueryEngine` via `java.util.ServiceLoader`.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + Testcontainers ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-redis-<version>-all.jar`.

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and the
connector manifest pins it:

- `connectors/redis/connector.json` → `driver.url` / `driver.fileName` / `driver.sha256`.
- CI (the `engines` job) rebuilds the JAR and **fails on pin drift**.
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes its bytecode — the
shaded JAR's hash changes. The loop is:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-redis-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in `connectors/redis/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The Redis
specifics:

- The host hands capabilities to `RedisQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages`, backed by the host `MessageSource` — the `error.redis.*` keys live
  in the host's `messages.properties`), credential decryption (`CredentialDecryptor`), the tuning
  config map (from `accessflow.proxy.engines.redis.*` via the host's generic `EngineConfigProperties`,
  AF-418: keys `connect-timeout`, `socket-timeout`, `max-pool-size`), and the host UTC clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the backend's
  `core.api` surface plus its own shaded dependencies.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `QueryExecutionFailedException`, `QueryExecutionTimeoutException`,
  `DatasourceConnectionTestException`, `UnrewritableRowSecurityException`).

## Governance model

- **Command parsing.** A submitted redis-cli command (`GET user:42`, `HGETALL session:abc`,
  `SCAN 0 MATCH orders:* COUNT 100`) is tokenized (quote-aware) and classified against a strict
  **allow-list** onto the host `QueryType` model: reads → `SELECT`; conditional-create
  (`SETNX`/`HSETNX`/`MSETNX`/`RENAMENX`) → `INSERT`; modifies → `UPDATE`; removals
  (`DEL`/`UNLINK`/`HDEL`/`LPOP`/…) → `DELETE`; admin (`FLUSHDB`/`SWAPDB`) → `DDL`. Permissions,
  routing policies, and approval plans then apply unchanged.
- **Forbidden up front (HTTP 422).** Server-side scripting (`EVAL`/`EVALSHA`/`SCRIPT`/`FUNCTION`,
  `FCALL`), blast-radius / admin (`CONFIG`, `FLUSHALL`, `SHUTDOWN`, `DEBUG`, `MIGRATE`, `CLUSTER`,
  `ACL`, `MODULE`, `CLIENT`, …), replication/persistence (`REPLICAOF`, `SAVE`, …), multi-command
  transactions (`MULTI`/`EXEC`/…), blocking reads (`BLPOP`/…), pub/sub, and connection-state mutation
  (`SELECT`, `MOVE`) — the key-value analogue of the SQL engine's `$where` ban. Anything outside both
  the allow-list and this set is rejected as an unsupported command.
- **Allow-list semantics.** `referencedTables` carries the key **prefix** (text before the first
  `:`): `orders:*` → `orders`, `user:42` → `user`, bare `foo` → `foo`. Schema allow-lists,
  permissions, and row-security policies target this key namespace.
- **Row security fails closed.** Row-level predicates have no meaning in a key-value model: when a
  row-security policy targets a referenced key prefix, execution is rejected with a distinct 422
  (`error.row_security_redis_unsupported`).
- **Field masking** applies to returned hash fields / values via the shared `core.api.ColumnMasker`,
  at parity with the SQL and MongoDB engines (`prefix.field` → bare `field` precedence).
- **Introspection** SCAN-samples a bounded number of keys, groups them by prefix into pseudo-tables,
  and reports hash field names (or a synthetic `value` column) typed by the Redis value type.

> Connection tuning is set at client construction from `accessflow.proxy.engines.redis.*`
> (`socket-timeout` bounds command latency); `SCAN` returns a single cursor page (set `truncated`
> when more remain) and the optional element-count forms of `LPOP`/`RPOP`/`SPOP` are supported.
