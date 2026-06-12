# AccessFlow Elasticsearch Query Engine

The on-demand Elasticsearch / OpenSearch engine plugin (issue [#420](https://github.com/bablsoft/accessflow/issues/420)).
Implements the backend's `core.api.QueryEngine` SPI for the **search** family and ships as a
**self-contained shaded JAR** (`accessflow-engine-elasticsearch-<version>-all.jar`) bundling **both**
low-level REST clients — Elastic's `org.elasticsearch.client:elasticsearch-rest-client` (Apache
HttpComponents 4) and OpenSearch's `org.opensearch.client:opensearch-rest-client` (Apache
HttpComponents 5) — each HttpComponents major relocated separately, plus a relocated Jackson. The
backend resolves it through the connector catalog exactly like a JDBC driver JAR — download from the
URL pinned in
[`connectors/elasticsearch/connector.json`](../../connectors/elasticsearch/connector.json), SHA-256
verification, cache under `ACCESSFLOW_DRIVER_CACHE`, isolated `URLClassLoader` — and discovers the
engine via `java.util.ServiceLoader`.

The plugin operates at the **low-level REST client**, manipulating raw JSON: it controls every
header (so it sends no Elastic product-check or version-compat media type — which is exactly what
lets the very same logic also drive OpenSearch), and the scripting ban is a JSON-tree scan, the
analogue of the MongoDB engine's `$where` ban.

## One JAR, two connectors (Elasticsearch + OpenSearch)

OpenSearch speaks the same REST API and Query DSL for the governed operations, so this single JAR
registers **two** `QueryEngine` providers in
`META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine`: `ElasticsearchQueryEngine`
(`engineId()` → `"elasticsearch"`) and the thin `OpenSearchQueryEngine extends
ElasticsearchQueryEngine` (`engineId()` → `"opensearch"`). They differ only in the low-level REST
client used — selected by `TransportFlavor` behind the `SearchTransport` abstraction; everything
else (parser, row security, masking, introspection) is shared. The host matches
`connectorId == engineId()` when ServiceLoading, so the same JAR (pinned by both
[`connectors/elasticsearch/connector.json`](../../connectors/elasticsearch/connector.json) and
[`connectors/opensearch/connector.json`](../../connectors/opensearch/connector.json)) backs both
connectors. A separate `DbType.OPENSEARCH` exists only because the connector catalog allows one
connector per non-CUSTOM dialect — behaviour is identical.

## Building

The plugin compiles against the backend's plain JAR (`core.api` SPI + DTOs, `provided` scope), so
install the backend first:

```bash
mvn -f ../../backend/pom.xml install -DskipTests   # installs com.bablsoft.accessflow:accessflow (plain jar)
mvn clean verify                                   # unit tests + Testcontainers ITs + shaded jar
```

The shaded artifact lands at `target/accessflow-engine-elasticsearch-<version>-all.jar`. The
Elasticsearch IT uses the `elasticsearch:9` image (`xpack.security.enabled=false`); the OpenSearch IT
uses a generic container on `opensearchproject/opensearch:2` (Testcontainers has no OpenSearch
module) — both take a minute or two to boot, so expect the first local run to be slow.

## Versioning and the SHA-256 pin

The plugin has its **own version line** (`pom.xml` `<version>`), independent of the application
release version. The build is **reproducible** (fixed `project.build.outputTimestamp`), so the
shaded JAR's SHA-256 is stable for a given source tree + JDK line and is pinned in **both**
connector manifests. CI (`.github/scripts/check-engine-pins.mjs`) fails on drift. When the engine —
or a `core.api` type it compiles against — changes, re-pin in the same PR:

```bash
mvn clean package
shasum -a 256 target/accessflow-engine-elasticsearch-*-all.jar
# bump <version> in pom.xml and update url / fileName / sha256 in BOTH
# connectors/elasticsearch/connector.json and connectors/opensearch/connector.json
```

## Host contract

`initialize(QueryEngineContext)` wires the engine from the host capabilities (Spring-free): the
`EngineMessages` (i18n keys `error.elasticsearch.*` live in the **host** `messages.properties`), the
`CredentialDecryptor` (decrypts `password_encrypted` / `api_key_encrypted` at client-build time
only), the per-engine `config` map (bound from `accessflow.proxy.engines.{elasticsearch,opensearch}.*`
— `connect-timeout` `PT10S`, `socket-timeout` `PT30S`), and the UTC `Clock`.

## Query envelope, row security, masking

Queries are an AccessFlow JSON envelope whose first command key names the operation and whose value
is the target index / pattern: `search`/`count`/`get`/`mget` → SELECT (get/mget lowered to a
`search` over an `ids` query), `index`/`bulk` → INSERT, `update_by_query` → UPDATE,
`delete_by_query` → DELETE, `create_index`/`put_mapping`/`delete_index` → DDL. `script` /
`script_fields` / `runtime_mappings` / Painless and cluster/system-index targets are rejected with
422. Row security wraps the user query in `bool.filter` (keyword fields only — `term` on analysed
`text` fails closed; writes into a policied index are rejected). Field masking via the shared
`core.api.ColumnMasker` applies recursively by dot-path so a `user.email` mask redacts the nested
leaf. Introspection reads `_mapping`, flagging a synthetic `_id` keyword column as the primary key.

See [`docs/05-backend.md` → Elasticsearch engine](../../docs/05-backend.md#elasticsearch-engine) and
the engine-author guide [`docs/15-engine-sdk.md`](../../docs/15-engine-sdk.md).
