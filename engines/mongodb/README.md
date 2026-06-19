# AccessFlow MongoDB Query Engine

The on-demand MongoDB engine plugin (issue [#414](https://github.com/bablsoft/accessflow/issues/414)).
Implements the backend's `core.api.QueryEngine` SPI and ships as a **self-contained shaded JAR**
(`accessflow-engine-mongodb-<version>-all.jar`) bundling `mongodb-driver-sync` and a relocated
Jackson. The backend resolves it through the connector catalog exactly like a JDBC driver JAR —
download from the URL pinned in [`connectors/mongodb/connector.json`](../../connectors/mongodb/connector.json),
SHA-256 verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and
discovers the `MongoQueryEngine` via `java.util.ServiceLoader`.

`MongoJson` parses query arguments with a relaxed Jackson reader (single quotes, unquoted keys,
comments, trailing commas) and falls back to MongoDB's own lenient reader when Jackson fails, so
shell extended-JSON constructors — `ObjectId(…)`, `ISODate(…)`, `new Date(…)`, `NumberLong(…)`,
`NumberDecimal(…)`, `UUID(…)` and canonical `$oid`/`$date` — also parse (common in AI-generated
`insertMany` drafts, AF-476). The fallback yields driver-native BSON types; the forbidden-operator
check still runs on the parsed tree.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + Testcontainers ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-mongodb-<version>-all.jar`.

## Versioning and the SHA-256 pin

The plugin has its **own version line**, independent of the application release version —
`release.yml` never bumps it. The build is **reproducible** (`project.build.outputTimestamp` is
fixed), so the shaded JAR's SHA-256 is stable for a given source tree and JDK line, and the
connector manifest pins it:

- `connectors/mongodb/connector.json` → `driver.url` / `driver.fileName` / `driver.sha256`.
- CI (the `engines` job) rebuilds the JAR and **fails on pin drift**.
- The release workflow re-verifies the pin and publishes the JAR to the `gh-pages` branch under
  `engines/`, where fresh installs download it from.

When you change the engine — or a `core.api` type it compiles against changes its bytecode — the
shaded JAR's hash changes. The loop is:

1. Bump `<version>` in `pom.xml` (the JAR is immutable once published at a version).
2. `mvn clean package`, then `shasum -a 256 target/accessflow-engine-mongodb-<new>-all.jar`.
3. Update `url`, `fileName`, and `sha256` in `connectors/mongodb/connector.json` in the same PR.

## Host ↔ plugin contract

The full engine-author guide is [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md). The
MongoDB specifics:

- The host hands capabilities to `MongoQueryEngine.initialize(QueryEngineContext)`: message
  resolution (`EngineMessages`, backed by the host `MessageSource` — the `error.mongo.*` keys live
  in the host's `messages.properties`), credential decryption (`CredentialDecryptor`), the tuning
  config map (from `accessflow.proxy.engines.mongodb.*` via the host's generic
  `EngineConfigProperties`, AF-418: keys `connect-timeout`, `server-selection-timeout`,
  `max-pool-size`; the legacy `ACCESSFLOW_PROXY_MONGO_*` env vars keep working as aliases), and the
  host UTC clock.
- The plugin must stay free of Spring, Lombok, and host-internal types; it may use only the
  backend's `core.api` surface plus its own shaded dependencies.
- Exceptions cross the boundary as the concrete `core.api` types (`InvalidSqlException`,
  `QueryExecutionFailedException`, `QueryExecutionTimeoutException`,
  `DatasourceConnectionTestException`).
