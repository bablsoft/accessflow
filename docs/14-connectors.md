# 14 — Connectors

AccessFlow proxies relational databases through JDBC drivers and NoSQL engines through
**engine plugins** (AF-414). Rather than hardcoding the supported databases in Java, the set of
databases is described declaratively by a **connector catalog** — a
repo-root [`connectors/`](../connectors/) folder, one subfolder per connector, each holding a
`connector.json` manifest and a logo. Adding or updating a supported database is a **data change**
(edit a manifest), not a code change.

This replaces the formerly hardcoded `DriverRegistry`. The catalog is bundled into the backend
image (on the classpath as `connectors/**`) and loaded at startup by
`proxy/internal/driver/ConnectorCatalog`. It is also published as a separate release artifact (see
[Release artifacts](#release-artifacts)).

## What a connector is

A **connector** is pre-defined driver metadata that an admin installs with one click from the
**Connectors** marketplace (`/admin/connectors`): the display name, logo, default port/SSL, JDBC-URL
template, the driver class, and where to fetch the artifact JAR (Maven coordinates or a direct URL)
plus its pinned SHA-256. The artifact is a JDBC driver JAR for `RELATIONAL` connectors and a shaded
**engine-plugin JAR** for non-RELATIONAL (NoSQL) connectors — both ride the same pipeline. Installing a
connector downloads, SHA-256-verifies, and caches the JAR. "Installed" is **derived** from JAR
cache presence (no separate table): a connector is `READY` when bundled or its JAR is cached,
`AVAILABLE` when downloadable, `UNAVAILABLE` when offline and not cached.

### Connector vs. uploaded driver

| | Connector | Uploaded driver |
|---|---|---|
| Source | Curated catalog (`connectors/`), global | Admin-supplied JAR, org-scoped (`custom_jdbc_driver`) |
| Install | One click; metadata is pre-defined | Manual upload; admin enters class + SHA-256 |
| Endpoint | `POST /datasources/connectors/{id}/install` | `POST /datasources/drivers` (multipart) |
| Datasource link | `datasource.connector_id` | `datasource.custom_driver_id` |

## Manifest format

Layout (folder name **must** equal the manifest `id`):

```
connectors/
  schema/connector.schema.json    # JSON Schema validated in CI
  <id>/
    connector.json
    logo.svg
```

The authoritative contract is [`connectors/schema/connector.schema.json`](../connectors/schema/connector.schema.json).
Fields: `schemaVersion` (=1), `id` (slug, == folder), `name`, `dbType` (one of `POSTGRESQL`,
`MYSQL`, `MARIADB`, `ORACLE`, `MSSQL`, `CUSTOM`, `MONGODB`, `COUCHBASE`, `REDIS`, `CASSANDRA`, `SCYLLADB`, `ELASTICSEARCH`, `OPENSEARCH`), `category` (`RELATIONAL` (default) for
SQL engines; `DOCUMENT`, `KEY_VALUE`, `WIDE_COLUMN`, `SEARCH`, or `GRAPH` for the NoSQL family —
AF-418), `vendor`, `description`, `documentationUrl`,
`logo`, `defaultPort`, `defaultSslMode`, `jdbcUrlTemplate` (`{host}`/`{port}`/`{database_name}`),
`driverClassName`, `bundled`, and a `driver` object (required unless `bundled`).

`category` drives the **SQL** vs **NoSQL** umbrella grouping in the connector marketplace and on
the website: `RELATIONAL` is the SQL family; every other value (`DOCUMENT`, `KEY_VALUE`,
`WIDE_COLUMN`, `SEARCH`, `GRAPH`) groups under NoSQL. For non-RELATIONAL connectors (e.g. MongoDB,
`category=DOCUMENT`), `jdbcUrlTemplate` and `driverClassName` are **omitted** — they connect through
a native engine, not JDBC — and the schema makes those two fields required only when `category` is
`RELATIONAL`. A non-RELATIONAL connector's `driver` artifact is not a JDBC driver but an
**engine-plugin JAR**: a shaded implementation of the `core.api.QueryEngine` SPI, discovered via
`java.util.ServiceLoader` from the downloaded JAR (the provider's `engineId()` must equal the
connector `id`, which must equal the `engines/<id>/` folder name). The engine-author guide is
[15-engine-sdk.md](./15-engine-sdk.md); see also
[05-backend.md → MongoDB engine](./05-backend.md#mongodb-engine).

`driver` is one of:

```jsonc
// Maven coordinate (classifier optional, for shaded "all-in-one" JARs)
{ "type": "maven", "groupId": "...", "artifactId": "...", "version": "...",
  "classifier": "all", "sha256": "<64 hex>" }
// Direct URL
{ "type": "url", "url": "https://…/foo.jar", "fileName": "foo.jar", "sha256": "<64 hex>" }
```

The JAR must be **self-contained** — it is loaded into an isolated `URLClassLoader` with no
transitive resolution, so pin a shaded JAR (use the `all` classifier) when one exists. Maven JARs
are fetched from `ACCESSFLOW_DRIVERS_REPOSITORY_URL` (default Maven Central); `url`-type artifacts
(such as the MongoDB engine plugin, served from this repo's `gh-pages` branch) are fetched from
their absolute URL — for air-gapped installs, pre-seed the cache instead.

The served copy of each logo also lives at `frontend/public/db-icons/<id>.svg` (the frontend serves
it statically). Keep the two copies in sync. See [`connectors/README.md`](../connectors/README.md)
for the authoring guide.

## Built-in connectors

| id | dbType | category | bundled | Driver |
|---|---|---|---|---|
| `postgresql` | POSTGRESQL | RELATIONAL | yes | on the application classpath |
| `mysql` | MYSQL | RELATIONAL | no | `com.mysql:mysql-connector-j` |
| `mariadb` | MARIADB | RELATIONAL | no | `org.mariadb.jdbc:mariadb-java-client` |
| `oracle` | ORACLE | RELATIONAL | no | `com.oracle.database.jdbc:ojdbc11` |
| `mssql` | MSSQL | RELATIONAL | no | `com.microsoft.sqlserver:mssql-jdbc` |
| `clickhouse` | CUSTOM | RELATIONAL | no | `com.clickhouse:clickhouse-jdbc:all` |
| `mongodb` | MONGODB | DOCUMENT | no | `accessflow-engine-mongodb-<v>-all.jar` engine plugin (native, not JDBC) |
| `couchbase` | COUCHBASE | DOCUMENT | no | `accessflow-engine-couchbase-<v>-all.jar` engine plugin (native, not JDBC) |
| `redis` | REDIS | KEY_VALUE | no | `accessflow-engine-redis-<v>-all.jar` engine plugin (native, not JDBC) |
| `cassandra` | CASSANDRA | WIDE_COLUMN | no | `accessflow-engine-cassandra-<v>-all.jar` engine plugin (native, not JDBC) |
| `scylladb` | SCYLLADB | WIDE_COLUMN | no | the **same** `accessflow-engine-cassandra-<v>-all.jar` (second `QueryEngine` provider) |
| `elasticsearch` | ELASTICSEARCH | SEARCH | no | `accessflow-engine-elasticsearch-<v>-all.jar` engine plugin (native, not JDBC) |
| `opensearch` | OPENSEARCH | SEARCH | no | the **same** `accessflow-engine-elasticsearch-<v>-all.jar` (second `QueryEngine` provider) |
| `dynamodb` | DYNAMODB | KEY_VALUE | no | `accessflow-engine-dynamodb-<v>-all.jar` engine plugin (native, not JDBC) |
| `neo4j` | NEO4J | GRAPH | no | `accessflow-engine-neo4j-<v>-all.jar` engine plugin (native, not JDBC) |

The first five map to first-class relational `DbType` dialects (dialect-aware SQL parsing, SSL
handling). ClickHouse is a **new SQL engine** beyond the built-in five: it carries `dbType=CUSTOM`
and is resolved through a per-connector classloader. A datasource for it stores `connector_id` and
the proxy builds the JDBC URL from the connector's `jdbcUrlTemplate` + host/port/database.

**MongoDB** is the NoSQL document connector. Since AF-414 it is **not** bundled: its engine ships
as the shaded plugin JAR built from [`engines/mongodb/`](../engines/mongodb/) (pinned by URL +
SHA-256 in the manifest, published to `gh-pages` under `engines/` on release) and is resolved on
demand exactly like a JDBC driver JAR. It carries no JDBC URL/driver class; the loaded engine opens
a per-datasource `MongoClient` from the standard host/port/database/credentials/SSL fields. The
plugin build is reproducible and CI fails when the built JAR's SHA-256 drifts from the manifest pin
(bump the plugin version and re-pin — see [`engines/mongodb/README.md`](../engines/mongodb/README.md)).
See [05-backend.md → MongoDB engine](./05-backend.md#mongodb-engine).

**Couchbase** is the second NoSQL document connector (AF-412), delivered the same way: the shaded
plugin JAR built from [`engines/couchbase/`](../engines/couchbase/) (own version line, reproducible
build, URL + SHA-256 pin in the manifest, published to `gh-pages` under `engines/` on release). It
speaks **SQL++ (N1QL)**. The datasource's `database_name` is the **bucket**; every statement runs
through the bucket's default-scope query context, so a bare `FROM users` resolves to
`<bucket>._default.users` and carries `users` in `referencedTables` (matching a collection-level
grant), while a fully-qualified `bucket.scope.collection` path is carried verbatim (matching an
exact-path grant, or an `allowedSchemas` entry on the bucket segment). Connections use
`couchbase://` (plain, KV port 11210 — the manifest default) or `couchbases://` (TLS, port 11207);
set the matching port or a verbatim URL override. See
[05-backend.md → Couchbase engine](./05-backend.md#couchbase-engine).

**Redis** is the NoSQL **key-value** connector (AF-419, `category=KEY_VALUE`), delivered the same
way: the shaded plugin JAR built from [`engines/redis/`](../engines/redis/) (own version line,
reproducible build, URL + SHA-256 pin in the manifest, published to `gh-pages` under `engines/` on
release), bundling the native [Jedis](https://github.com/redis/jedis) driver. Users submit redis-cli
commands (`GET user:42`, `HGETALL session:abc`, `SCAN 0 MATCH orders:* COUNT 100`) that classify
onto the standard `QueryType` model; server-side scripting and blast-radius commands
(`EVAL`/`SCRIPT`/`FUNCTION`, `CONFIG`, `FLUSHALL`, `SHUTDOWN`, …) are rejected at submission with
422. `referencedTables` carries the key **prefix** (`orders:*` → `orders`), so allow-lists and
permissions target a key namespace. Row-security policies on a Redis datasource **fail closed**
(row predicates have no key-value meaning); field masking applies to returned hash fields / values.
The datasource's `database_name` is the numeric DB index (default `0`); connections use `redis://`
(plain, port 6379 — the manifest default) or `rediss://` (TLS). See
[05-backend.md → Redis engine](./05-backend.md#redis-engine).

**Cassandra** is the NoSQL **wide-column** connector (AF-421, `category=WIDE_COLUMN`), delivered the
same way: the shaded plugin JAR built from [`engines/cassandra/`](../engines/cassandra/) (own version
line, reproducible build, URL + SHA-256 pin in the manifest, published to `gh-pages` under `engines/`
on release), bundling the native [DataStax Java driver](https://github.com/apache/cassandra-java-driver)
(with a relocated Netty / Typesafe Config / HdrHistogram). Users submit **CQL**; SELECT/INSERT/UPDATE/
DELETE and `CREATE`/`ALTER`/`DROP` of a table/keyspace/index/type/materialized view + `TRUNCATE`
classify onto the standard `QueryType` model, while `BEGIN … BATCH` and `CREATE`/`DROP FUNCTION`/
`AGGREGATE` (server-side code) are rejected with distinct 422s. The datasource's `database_name` is
the **keyspace**; an extra **`local_datacenter`** field (the driver's load-balancing datacenter) is
required. Row-security predicates are spliced into the WHERE clause **only** on partition/clustering
key columns with the operators CQL can filter (`=, IN, <, <=, >, >=`); anything else **fails closed**
rather than injecting `ALLOW FILTERING`. Default port 9042. See
[05-backend.md → Cassandra engine](./05-backend.md#cassandra-engine).

**ScyllaDB** is CQL-compatible and reuses the **very same** Cassandra plugin JAR — the JAR registers
two `QueryEngine` providers (`engineId` `cassandra` and `scylladb`), and both
`connectors/cassandra/connector.json` and `connectors/scylladb/connector.json` pin the same URL +
SHA-256. It exists as a separate connector + `DbType.SCYLLADB` only because the catalog allows one
connector per non-`CUSTOM` dialect; behaviour is identical to Cassandra.

**Amazon DynamoDB** is the NoSQL **key-value** connector (AF-422, `category=KEY_VALUE`), delivered the
same way: the shaded plugin JAR built from [`engines/dynamodb/`](../engines/dynamodb/) (own version
line, reproducible build, URL + SHA-256 pin in the manifest, published to `gh-pages` under `engines/`
on release), bundling the native [AWS SDK for Java v2](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html)
DynamoDB client over the url-connection HTTP client (no Netty). Users submit **PartiQL**;
SELECT/INSERT/UPDATE/DELETE classify onto the standard `QueryType` model, and table management
arrives as a JSON command document (`CreateTable`/`DeleteTable`/`UpdateTable` → DDL), while
transaction/batch statements are rejected with 422. It is the first engine whose **connection is
cloud credentials + region, not host/port**: `database_name` is the AWS region, `username` the access
key id, `password_encrypted` the secret access key, and `jdbc_url_override` an optional custom endpoint
(DynamoDB Local / VPC). Row-security predicates are spliced into the PartiQL WHERE clause (positional
`?` parameters; any attribute, since DynamoDB filters via Scan), with INSERT-into-policied and deny-all
failing closed. Field masking applies post-fetch, recursively by dot-path. Default port 8000 (DynamoDB
Local; AWS uses the SDK regional endpoint). See [05-backend.md → DynamoDB engine](./05-backend.md#dynamodb-engine).

**Neo4j** is the NoSQL **graph** connector (AF-423, `category=GRAPH`), delivered the same way: the
shaded plugin JAR built from [`engines/neo4j/`](../engines/neo4j/) (own version line, reproducible
build, URL + SHA-256 pin in the manifest, published to `gh-pages` under `engines/` on release),
bundling the native [Neo4j Java driver](https://neo4j.com/docs/java-manual/current/) and its
Bolt-connection stack with a relocated Netty / Project Reactor / reactive-streams. Users submit
**Cypher** over Bolt; the query type is the strongest write clause present (DELETE/REMOVE → DELETE,
CREATE/MERGE → INSERT, SET → UPDATE, else a `MATCH … RETURN` / `SHOW` read → SELECT), with
index/constraint/database/role schema commands → DDL. `LOAD CSV`, procedure calls outside a small
read-only allow-list, and multi-statement input are rejected with 422. Connection is host/port +
`database_name` (the Neo4j database) with the SSL mode encoded in the Bolt scheme, **or** a full
`bolt://` / `neo4j+s://` URI in `jdbc_url_override` (Aura / clustered routing). Row-security
predicates are ANDed onto each `MATCH`'s `WHERE` (Cypher named parameters; node-label policies),
failing closed on anonymous / write shapes; field masking applies post-fetch, label-aware and
recursive. Default port 7687 (Bolt). See [05-backend.md → Neo4j engine](./05-backend.md#neo4j-engine).

## Resolution at query time

For relational datasources, `proxy/internal/DatasourcePoolFactory` resolves the JDBC driver in three
lanes:

1. `custom_driver_id` set → admin-uploaded JAR (`resolveCustom`, per-upload classloader).
2. `connector_id` set → catalog connector (`resolveConnector`, per-connector classloader); the
   JDBC URL is built from the connector template.
3. otherwise → one of the five relational dialects (`resolve(dbType)`).

For engine-managed types (`db_type=MONGODB`, `db_type=COUCHBASE`, `db_type=REDIS`), `DefaultQueryExecutor` /
`DefaultQueryParser` / the admin connection-test
and introspection paths resolve the engine from `core.api.QueryEngineCatalog`
(`proxy/internal/driver/DefaultQueryEngineCatalog`): the connector's plugin JAR is ensured in the
shared driver cache, loaded into an isolated classloader, and the `QueryEngine` is discovered via
`ServiceLoader` and initialized once. Offline with no cached JAR fails with the same
`OFFLINE_CACHE_MISS` error a JDBC connector would produce.

## Endpoints

See [04-api-spec.md → Connector endpoints](./04-api-spec.md#connector-endpoints).

- `GET /api/v1/datasources/connectors` — list the catalog with install status (ADMIN).
- `POST /api/v1/datasources/connectors/{id}/install` — download + verify + cache the driver,
  returns the updated status (ADMIN). `404` for an unknown id, `422` on download/checksum failure.

Installed connectors also appear in `GET /api/v1/datasources/types` (the datasource wizard feed):
the five dialects as `source=bundled`, catalog `CUSTOM` connectors as `source=connector`.

## Release artifacts

On release, the catalog is published to the `gh-pages` branch alongside the Helm chart (GitHub
Release asset uploads are blocked by the repo's immutable-releases policy):

- `connectors/connectors-bundle-<version>.tar.gz` — the full `connectors/` folder.
- `connectors/connectors-index.json` — a generated summary (`{version, connectors:[{id, name,
  dbType, vendor, bundled, driver:{type, sha256}}]}`), overwritten each release.
- `engines/accessflow-engine-mongodb-<pluginVersion>-all.jar` — the MongoDB engine plugin
  (AF-414). The plugin version is independent of the release version; the upload is idempotent
  because the build is reproducible. The release fails if the built JAR's SHA-256 does not match
  the manifest pin.

Served from `https://<owner>.github.io/accessflow/connectors/` and
`https://<owner>.github.io/accessflow/engines/`.

## Persistence and air-gap

Installed connectors survive restarts via the driver-cache volume (`ACCESSFLOW_DRIVER_CACHE`; the
Helm chart's `driverCache.persistence` PVC). Engine-plugin JARs cache in the **same directory** as
JDBC driver JARs. For air-gapped installs, pre-seed the cache and set
`ACCESSFLOW_DRIVERS_OFFLINE=true` — connectors then report `UNAVAILABLE` unless their JAR is already
cached. To pre-seed the MongoDB engine, drop `accessflow-engine-mongodb-<v>-all.jar` (from the
gh-pages `engines/` folder, or built locally with `mvn -f engines/mongodb/pom.xml package` after a
backend `install` — the reproducible build matches the pinned SHA-256) into the cache directory.
