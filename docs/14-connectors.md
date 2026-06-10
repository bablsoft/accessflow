# 14 — Connectors

AccessFlow proxies databases through JDBC drivers. Rather than hardcoding the supported
databases in Java, the set of databases is described declaratively by a **connector catalog** — a
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
template, the driver class, and where to fetch the driver JAR (Maven coordinates or a direct URL)
plus its pinned SHA-256. Installing a connector downloads, SHA-256-verifies, and caches the driver
JAR — the same machinery the bundled dialects already used. "Installed" is **derived** from
driver-JAR cache presence (no separate table): a connector is `READY` when bundled or its JAR is
cached, `AVAILABLE` when downloadable, `UNAVAILABLE` when offline and not cached.

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
`MYSQL`, `MARIADB`, `ORACLE`, `MSSQL`, `CUSTOM`, `MONGODB`), `category` (`RELATIONAL` (default) for
SQL engines, `DOCUMENT` for NoSQL), `vendor`, `description`, `documentationUrl`,
`logo`, `defaultPort`, `defaultSslMode`, `jdbcUrlTemplate` (`{host}`/`{port}`/`{database_name}`),
`driverClassName`, `bundled`, and a `driver` object (required unless `bundled`).

`category` separates the **SQL** vs **NoSQL** sections in the connector marketplace and on the
website. For `category=DOCUMENT` connectors (MongoDB), `jdbcUrlTemplate` and `driverClassName` are
**omitted** — they connect through a native driver, not JDBC — and the schema makes those two fields
required only when `category` is `RELATIONAL`.

`driver` is one of:

```jsonc
// Maven coordinate (classifier optional, for shaded "all-in-one" JARs)
{ "type": "maven", "groupId": "...", "artifactId": "...", "version": "...",
  "classifier": "all", "sha256": "<64 hex>" }
// Direct URL
{ "type": "url", "url": "https://…/foo.jar", "fileName": "foo.jar", "sha256": "<64 hex>" }
```

The driver JAR must be **self-contained** — it is loaded into an isolated `URLClassLoader` with no
transitive resolution, so pin a shaded JAR (use the `all` classifier) when one exists. Maven JARs
are fetched from `ACCESSFLOW_DRIVERS_REPOSITORY_URL` (default Maven Central).

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
| `mongodb` | MONGODB | DOCUMENT | yes | bundled `org.mongodb:mongodb-driver-sync` (native, not JDBC) |

The first five map to first-class relational `DbType` dialects (dialect-aware SQL parsing, SSL
handling). ClickHouse is a **new SQL engine** beyond the built-in five: it carries `dbType=CUSTOM`
and is resolved through a per-connector classloader. A datasource for it stores `connector_id` and
the proxy builds the JDBC URL from the connector's `jdbcUrlTemplate` + host/port/database.

**MongoDB** is the NoSQL document connector. It is `bundled` (the native driver ships in the image,
so there is no driver download/install — `install` is a no-op that reports `READY`) and carries no
JDBC URL/driver class. The proxy never resolves a JDBC driver for it; instead the
`proxy.internal.mongo.MongoClientManager` opens a per-datasource `MongoClient` from the standard
host/port/database/credentials/SSL fields. See [05-backend.md → MongoDB engine](./05-backend.md#mongodb-engine).

## Resolution at query time

For relational datasources, `proxy/internal/DatasourcePoolFactory` resolves the JDBC driver in three
lanes:

1. `custom_driver_id` set → admin-uploaded JAR (`resolveCustom`, per-upload classloader).
2. `connector_id` set → catalog connector (`resolveConnector`, per-connector classloader); the
   JDBC URL is built from the connector template.
3. otherwise → one of the five relational dialects (`resolve(dbType)`).

For `db_type=MONGODB` there is no driver resolution: `DefaultQueryExecutor` dispatches to
`MongoQueryExecutor`, which obtains a cached `MongoClient` from `MongoClientManager`.

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

Served from `https://<owner>.github.io/accessflow/connectors/`.

## Persistence and air-gap

Installed connectors survive restarts via the driver-cache volume (`ACCESSFLOW_DRIVER_CACHE`; the
Helm chart's `driverCache.persistence` PVC). For air-gapped installs, pre-seed the cache and set
`ACCESSFLOW_DRIVERS_OFFLINE=true` — connectors then report `UNAVAILABLE` unless their JAR is already
cached.
