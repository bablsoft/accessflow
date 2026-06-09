# AccessFlow Connectors

A **connector** is a declarative descriptor for a database AccessFlow can proxy. It tells the
application everything it needs to present the database in the UI and to download + load the right
JDBC driver: the display name, logo, default port, SSL mode, JDBC-URL template, the driver class,
and where to fetch the driver JAR (Maven coordinates or a direct URL) plus its pinned SHA-256.

This folder is the **single source of truth** for the connector catalog. It replaces the previously
hardcoded `DriverRegistry` in the backend. At build time it is bundled into the backend image (on the
classpath as `connectors/**`) and loaded at startup by `ConnectorCatalog`
(`proxy/internal/driver/`). It is also published as a release artifact — see
[`docs/14-connectors.md`](../docs/14-connectors.md).

Adding or updating a supported database is a **data change** (edit/add a manifest here), not a code
change.

## Layout

```
connectors/
  schema/connector.schema.json    # JSON Schema validated in CI
  <id>/
    connector.json                # the manifest (see schema)
    logo.svg                      # the connector logo
```

The folder name **must** equal the manifest `id`. The served copy of each logo also lives at
`frontend/public/db-icons/<id>.svg` (the frontend serves it statically; no auth needed for an
`<img>` tag). Keep the two copies in sync.

## Manifest fields

See [`schema/connector.schema.json`](schema/connector.schema.json) for the authoritative contract.
Summary:

| Field | Required | Notes |
|-------|----------|-------|
| `schemaVersion` | yes | Always `1`. |
| `id` | yes | Slug, `^[a-z0-9][a-z0-9-]*$`, equals folder name. |
| `name` | yes | Display name. |
| `dbType` | yes | One of `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `MSSQL`, `CUSTOM`. New engines beyond the built-in five use `CUSTOM`. |
| `vendor` | no | Shown on the catalog card. |
| `description` | no | One-line blurb on the card. |
| `documentationUrl` | no | Linked from the card. |
| `logo` | yes | File name within the connector folder. |
| `defaultPort` | yes | Pre-filled in the datasource wizard. `0` for URL-only engines. |
| `defaultSslMode` | yes | `SslMode` value. |
| `jdbcUrlTemplate` | yes | Uses `{host}`, `{port}`, `{database_name}` placeholders. |
| `driverClassName` | yes | The JDBC `java.sql.Driver` implementation FQCN. |
| `bundled` | yes | `true` only for PostgreSQL (driver is on the application classpath). |
| `driver` | unless bundled | Download descriptor — `maven` or `url` (below). |

### `driver` — Maven coordinate

```json
"driver": {
  "type": "maven",
  "groupId": "com.clickhouse",
  "artifactId": "clickhouse-jdbc",
  "version": "0.9.0",
  "classifier": "all",
  "sha256": "aad21a6b…"
}
```

`classifier` is optional; use it for shaded "all-in-one" JARs (the driver JAR must be self-contained
— it is loaded into an isolated `URLClassLoader` with no transitive resolution). The JAR is fetched
from `ACCESSFLOW_DRIVERS_REPOSITORY_URL` (default `https://repo1.maven.org/maven2`).

### `driver` — direct URL

```json
"driver": {
  "type": "url",
  "url": "https://example.com/path/foo-jdbc-1.2.3-all.jar",
  "fileName": "foo-jdbc-1.2.3-all.jar",
  "sha256": "…64 hex…"
}
```

## Authoring a new connector

1. `mkdir connectors/<id>` and add `connector.json` + `logo.svg`.
2. Copy the logo to `frontend/public/db-icons/<id>.svg`.
3. Pick the right `dbType`: one of the five built-in dialects, or `CUSTOM` for anything else.
4. Compute the driver JAR's SHA-256 (`shasum -a 256 <jar>`) and pin it. Pin a self-contained JAR
   (use the shaded/`all` classifier when one exists).
5. Validate against the schema and confirm the folder name equals `id`. CI runs this check.
6. For `CUSTOM` connectors, the datasource wizard builds the JDBC URL from `jdbcUrlTemplate` and
   stores the connector id on the datasource (`connector_id`); the proxy loads the driver into a
   per-connector classloader.

## Connector vs. uploaded driver

A **connector** is pre-defined, global metadata that an admin installs with one click (the JAR is
downloaded and verified for them). An **uploaded driver** (`POST /datasources/drivers`) is a raw JAR
an admin supplies manually for their organization, entering the class/SHA-256 by hand. Connectors are
the curated catalog; uploads are the escape hatch.
