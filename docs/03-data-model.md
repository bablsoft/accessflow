# 03 — Data Model

All entities are stored in AccessFlow's **internal PostgreSQL database**. Customer databases are never written to except through the proxied, approved query path.

> **Naming convention:** All tables use `snake_case`. All primary keys are `UUID`. All timestamps are `TIMESTAMPTZ`.

> **Startup bootstrap.** The `bootstrap` module (see [docs/05-backend.md → "Startup bootstrap"](05-backend.md#startup-bootstrap-env-driven-admin-config) and [docs/09-deployment.md → "Bootstrap configuration"](09-deployment.md#bootstrap-configuration)) seeds the rows below from `ACCESSFLOW_BOOTSTRAP_*` env vars on every backend start. No new tables / columns / enums are introduced for that feature — bootstrap reuses the existing unique constraints (`(organization_id, name)`, `(organization_id, provider)`, or singleton-per-org) as the upsert keys.

---

## organizations

Represents a tenant. A deployment hosts **one or more** organizations, each fully isolated — every
other entity is scoped by `organization_id`, and the request principal's org is always derived from
the JWT, never from the request body. Organizations are first-class, manageable resources: platform
admins (see `users.platform_admin`) create, configure, disable, and enable them across the cluster
through the `/api/v1/platform/organizations` endpoints (see [04-api-spec.md → Platform Organizations](04-api-spec.md#platform-organizations) and [07-security.md → Multi-tenant isolation](07-security.md#multi-tenant-isolation-af-456)). Per-org quotas cap how much each tenant may consume.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `name` | VARCHAR(255) NOT NULL |
| `slug` | VARCHAR(100) UNIQUE — URL-safe identifier |
| `disabled` | BOOLEAN NOT NULL DEFAULT false (AF-456) — when true the tenant is kill-switched: its users are blocked at login (local + SSO) and every authenticated request is rejected by a lightweight per-request org-status lookup, so disabling takes effect immediately (no cache). |
| `max_datasources` | INTEGER nullable (AF-456) — per-org cap on datasources. NULL or 0 = unlimited. Enforced at datasource creation; breach → HTTP 409 `QUOTA_EXCEEDED`. |
| `max_users` | INTEGER nullable (AF-456) — per-org cap on active users. NULL or 0 = unlimited. Enforced at user creation and invitation issuance (counts active users); breach → HTTP 409 `QUOTA_EXCEEDED`. |
| `max_queries_per_day` | INTEGER nullable (AF-456) — per-org cap on query submissions. NULL or 0 = unlimited. Enforced as a rolling trailing-24h count over `query_requests` (no counter table, no reset job); breach → HTTP 409 `QUOTA_EXCEEDED`. |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

The `disabled` / `max_*` columns are added by `V87__org_isolation_quotas_platform_admin.sql`.

---

## users

Platform users. Can be created locally or auto-provisioned via SAML.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `email` | VARCHAR(255) UNIQUE NOT NULL |
| `display_name` | VARCHAR(255) |
| `password_hash` | VARCHAR — null if SSO-only user |
| `auth_provider` | ENUM: `LOCAL` \| `SAML` \| `OAUTH2` |
| `saml_subject` | VARCHAR — SAML NameID, nullable |
| `role` | ENUM: `ADMIN` \| `REVIEWER` \| `ANALYST` \| `READONLY` \| `AUDITOR` (`AUDITOR` added in V91 — dedicated read-only compliance role, AF-459) |
| `platform_admin` | BOOLEAN NOT NULL DEFAULT false (AF-456) — orthogonal super-admin flag (**not** a fifth role). A platform admin keeps their home-org `role` and is additionally granted the Spring Security authority `PLATFORM_ADMIN`, which unlocks the cross-org `/api/v1/platform/organizations` management plane. The JWT carries a `platform_admin` claim and the login / `GET /me` user object includes a `platform_admin` boolean. The bootstrap admin and the first-run setup-wizard admin are provisioned as platform admins (a pre-existing bootstrap admin is promoted on an upgrade re-run). Added by `V87__org_isolation_quotas_platform_admin.sql`. |
| `is_active` | BOOLEAN DEFAULT true |
| `last_login_at` | TIMESTAMPTZ |
| `preferred_language` | VARCHAR(20) — BCP-47 code (`en`, `es`, `de`, `fr`, `zh-CN`, `ru`, `hy`); nullable, falls back to the org default |
| `totp_secret_encrypted` | VARCHAR(512) — AES-256-GCM ciphertext of the TOTP shared secret. Set during enrolment, cleared on disable. Null when 2FA is not enabled. |
| `totp_enabled` | BOOLEAN NOT NULL DEFAULT false — flipped to true only after the user confirms enrolment with a valid code |
| `totp_backup_codes_encrypted` | TEXT — AES-256-GCM ciphertext of a JSON array of bcrypt hashes (one per single-use recovery code). Codes are removed from the array as they're consumed. Null when 2FA is not enabled. |
| `attributes` | JSONB NOT NULL DEFAULT `'{}'` (AF-380) — admin-editable per-user attribute map, resolvable in row-security predicates as `:user.<key>`. Set via the user admin API; **not** synced from the IdP. Added by `V61__add_users_attributes.sql`. |
| `created_at` | TIMESTAMPTZ |

---

## api_keys

Per-user API keys used to authenticate the AccessFlow MCP server and other programmatic clients
without a browser session. Keys are issued once (plaintext shown on creation only), stored as a
SHA-256 hash, and revocable individually. A key inherits the owning user's role and datasource
permissions exactly — there is no separate scope model.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` ON DELETE CASCADE |
| `user_id` | FK → `users` ON DELETE CASCADE — the owning user |
| `name` | VARCHAR(100) NOT NULL — user-supplied label (UNIQUE per `user_id`) |
| `key_prefix` | VARCHAR(16) NOT NULL — first 12 chars of the raw key (e.g. `af_kQ7abcde`), shown in the UI for identification |
| `key_hash` | VARCHAR(128) NOT NULL UNIQUE — SHA-256 hex of the raw key; the source of truth used by the auth filter |
| `expires_at` | TIMESTAMPTZ — optional expiry; nullable for non-expiring keys |
| `last_used_at` | TIMESTAMPTZ — bumped on each successful authentication |
| `revoked_at` | TIMESTAMPTZ — non-null when the key has been revoked; revoked keys never authenticate |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT now() |

**Indexes**
- `idx_api_keys_user (user_id)` — user-scoped list view
- `idx_api_keys_org (organization_id)` — org-scoped cleanup on org delete
- `idx_api_keys_active_hash (key_hash) WHERE revoked_at IS NULL` — fast filter lookups

The raw key uses the format `af_<32-byte base64url, no padding>` (~38 chars). The plaintext is
**never persisted** — only the `key_hash` and `key_prefix` are. See `docs/07-security.md` →
"API key authentication" and `docs/13-mcp.md` for the full lifecycle and auth flow.

---

## datasources

A customer database that AccessFlow proxies. Credentials are stored encrypted.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `name` | VARCHAR(255) — human-readable label; **UNIQUE per organization** (case-insensitive at the service layer) |
| `db_type` | ENUM: `POSTGRESQL` \| `MYSQL` \| `MARIADB` \| `ORACLE` \| `MSSQL` \| `CUSTOM` \| `MONGODB` \| `COUCHBASE` \| `REDIS` \| `CASSANDRA` \| `SCYLLADB` \| `ELASTICSEARCH` \| `OPENSEARCH` \| `DYNAMODB` \| `NEO4J` — `CUSTOM` is paired with either `connector_id` (a catalog connector such as ClickHouse) or `custom_driver_id` + `jdbc_url_override` (an uploaded driver). `MONGODB` is the NoSQL document engine: a native (non-JDBC) connector whose engine plugin is resolved on demand through the connector catalog (AF-414), using the standard `host`/`port`/`database_name`/`username`/`password`/`ssl_mode` fields (no `connector_id`, no `jdbc_url_override`). `COUCHBASE` is the second NoSQL document engine (AF-412, migration `V72`): the SQL++ engine plugin, same on-demand model, with `database_name` holding the bucket. `REDIS` is the NoSQL **key-value** engine (AF-419, migration `V74`, category `KEY_VALUE`): the native Jedis engine plugin, same on-demand model, with `database_name` holding the numeric DB index (default `0`). `CASSANDRA` is the NoSQL **wide-column** engine (AF-421, migration `V75`, category `WIDE_COLUMN`): the native DataStax-driver CQL engine plugin, same on-demand model, with `database_name` holding the keyspace and the `local_datacenter` column required. `SCYLLADB` (migration `V76`) is CQL-compatible and served by the very same Cassandra plugin JAR (which registers a second `QueryEngine` provider with `engineId="scylladb"`); it has its own `db_type` only because the catalog allows one connector per non-`CUSTOM` dialect. `ELASTICSEARCH` is the NoSQL **search** engine (AF-420, migration `V78`, category `SEARCH`): the native low-level-REST-client engine plugin, same on-demand model, queried with a JSON envelope; `database_name` is optional (it only scopes introspection — the index is named in the query) and the optional `api_key_encrypted` column carries an API key as an alternative to `username`/`password`. `OPENSEARCH` (migration `V79`) is wire-compatible and served by the very same Elasticsearch plugin JAR (which registers a second `QueryEngine` provider with `engineId="opensearch"`); it has its own `db_type` for the same one-connector-per-dialect reason. `DYNAMODB` is the NoSQL **key-value** engine (AF-422, migration `V81`, category `KEY_VALUE`): the native AWS-SDK-v2 PartiQL engine plugin, same on-demand model, but its connection is **cloud credentials + region, not host/port** — `database_name` holds the AWS region, `username` the access key id, `password_encrypted` the secret access key, and `jdbc_url_override` an optional custom endpoint (DynamoDB Local / VPC); `host`/`port` are unused. `NEO4J` is the NoSQL **graph** engine (AF-423, migration `V82`, category `GRAPH`): the native Neo4j-Java-driver Cypher engine plugin over Bolt, same on-demand model, with `database_name` holding the Neo4j database (required) and the SSL mode encoded in the Bolt scheme; like DynamoDB it also accepts a full `bolt://` / `neo4j+s://` URI in `jdbc_url_override` (Aura / clustered routing) in place of host/port. See [14-connectors.md](./14-connectors.md), [05-backend.md → MongoDB engine](./05-backend.md#mongodb-engine), [05-backend.md → Couchbase engine](./05-backend.md#couchbase-engine), [05-backend.md → Redis engine](./05-backend.md#redis-engine), [05-backend.md → Cassandra engine](./05-backend.md#cassandra-engine), [05-backend.md → Elasticsearch engine](./05-backend.md#elasticsearch-engine), [05-backend.md → DynamoDB engine](./05-backend.md#dynamodb-engine), and [05-backend.md → Neo4j engine](./05-backend.md#neo4j-engine). Added by migration `V71`. |
| `host` | VARCHAR(255) — nullable; required for bundled `db_type`s, absent when `db_type=CUSTOM` |
| `port` | INTEGER — nullable; same rule as `host` |
| `database_name` | VARCHAR(255) — nullable; same rule as `host` |
| `username` | VARCHAR(255) — service account username |
| `password_encrypted` | TEXT — AES-256-GCM encrypted at rest |
| `ssl_mode` | ENUM: `DISABLE` \| `REQUIRE` \| `VERIFY_CA` \| `VERIFY_FULL` |
| `connection_pool_size` | INTEGER DEFAULT 10 |
| `max_rows_per_query` | INTEGER DEFAULT 1000 — hard cap on SELECT result rows. Surfaced to the proxy module via `DatasourceConnectionDescriptor.maxRowsPerQuery` and clamped at execution time to `accessflow.proxy.execution.max-rows`. |
| `require_review_reads` | BOOLEAN DEFAULT false — force review even for SELECT |
| `require_review_writes` | BOOLEAN DEFAULT true — force review for INSERT/UPDATE/DELETE |
| `review_plan_id` | FK → `review_plans` |
| `ai_analysis_enabled` | BOOLEAN DEFAULT true |
| `ai_config_id` | FK → `ai_config(id)` NULL, ON DELETE SET NULL — which AI configuration runs analysis (and text-to-SQL generation) for this datasource. Required (and enforced by the service layer) when `ai_analysis_enabled = true` **or** `text_to_sql_enabled = true`. |
| `text_to_sql_enabled` | BOOLEAN DEFAULT false — when true, users may generate a SQL draft from a natural-language prompt via `POST /api/v1/queries/generate-sql` (AF-335). Reuses `ai_config_id`; independent of `ai_analysis_enabled`. The generated SQL is only a draft — it is still submitted through the normal pipeline, so all governance applies. |
| `custom_driver_id` | FK → `custom_jdbc_driver(id)` NULL, ON DELETE RESTRICT — when set, the proxy uses the uploaded driver's per-driver classloader instead of a catalog connector. A `CUSTOM` datasource sets exactly one of `custom_driver_id` or `connector_id`. |
| `connector_id` | VARCHAR(64) NULL — references a catalog connector by its manifest id (e.g. `clickhouse`; see [14-connectors.md](./14-connectors.md)). Set for a `CUSTOM` datasource backed by an installed connector: the proxy loads the connector's cached driver into a per-connector classloader and builds the JDBC URL from the connector's template + host/port/database. Null for the five dialects and for uploaded-driver datasources. Only allowed when `db_type=CUSTOM`. |
| `jdbc_url_override` | TEXT NULL — free-form JDBC connection string; used by an uploaded-driver `CUSTOM` datasource (required there, rejected for any bundled `db_type` and for connector-backed datasources, which build their URL from the connector template). |
| `read_replica_jdbc_url` | TEXT NULL — when set, SELECT queries are routed to a sibling HikariCP pool built from this URL. INSERT/UPDATE/DELETE/DDL always hit the primary. Reuses the primary's driver class. |
| `read_replica_username` | VARCHAR(255) NULL — username for the replica pool. When `NULL` the primary `username` is reused. |
| `read_replica_password_encrypted` | TEXT NULL — AES-256-GCM encrypted; same key (`ENCRYPTION_KEY`) as `password_encrypted`. When `NULL`, the primary `password_encrypted` is reused. `@JsonIgnore` on the entity. |
| `local_datacenter` | VARCHAR(255) NULL (AF-421, migration `V77`) — the Cassandra / ScyllaDB driver's load-balancing datacenter (`withLocalDatacenter(...)`). NULL for every other dialect; the service layer **requires** it when `db_type` is `CASSANDRA` or `SCYLLADB`. |
| `api_key_encrypted` | TEXT NULL (AF-420, migration `V80`) — AES-256-GCM-encrypted API key for the search engines (`ELASTICSEARCH` / `OPENSEARCH`), sent as `Authorization: ApiKey`. `@JsonIgnore`, never returned in an API response. NULL for basic-auth search datasources and every other dialect; the service layer requires **either** `username`+`password` **or** `api_key` for a search datasource. |
| `is_active` | BOOLEAN DEFAULT true |
| `created_at` | TIMESTAMPTZ |

> **Constraint:** `UNIQUE (organization_id, name)` — added in `V10__datasource_unique_name_per_org.sql`. Attempting to create or rename a datasource into an existing name in the same organization returns HTTP 409 with `error: DATASOURCE_NAME_ALREADY_EXISTS`.

---

## custom_jdbc_driver

Per-organization admin-uploaded JDBC driver JARs. Powers both:
1. **Drop-in overrides** — uploaded entries take precedence over the bundled registry when a
   datasource sets `custom_driver_id`. Useful for community-driver forks, vendor builds, or
   newer driver versions.
2. **Fully dynamic datasources** — when `target_db_type=CUSTOM`, the upload backs a
   `db_type=CUSTOM` datasource with a free-form JDBC URL (no host/port/database_name).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `vendor_name` | VARCHAR(100) — display label (e.g. "Snowflake", "Acme Custom Build") |
| `target_db_type` | `db_type` ENUM — the dialect the upload speaks. `CUSTOM` means free-form JDBC URL. |
| `driver_class` | VARCHAR(255) — fully-qualified Java class name of the JDBC driver inside the JAR |
| `jar_filename` | VARCHAR(255) — original filename for display |
| `jar_sha256` | VARCHAR(64) — hex SHA-256; verified server-side at upload and at every classloader hit |
| `jar_size_bytes` | BIGINT |
| `storage_path` | TEXT — relative path under `${ACCESSFLOW_DRIVER_CACHE}` (typically `custom/<org_id>/<driver_id>.jar`) |
| `uploaded_by` | FK → `users(id)` |
| `created_at` | TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP |

**Constraints**
- `UNIQUE (organization_id, jar_sha256)` — re-upload of the same JAR returns HTTP 409 with `CUSTOM_DRIVER_DUPLICATE`.
- Index `idx_custom_jdbc_driver_org_dbtype (organization_id, target_db_type)` — powers `GET /datasources/types` org-scoped lookup.
- Datasources reference this table via `datasources.custom_driver_id` with `ON DELETE RESTRICT`; deleting a driver that any datasource still binds to returns HTTP 409 with `CUSTOM_DRIVER_IN_USE` (body includes `referencedBy` array of datasource ids).

The JAR file on disk is **not** encrypted — it is byte-identical to what the admin uploaded.
SHA-256 + admin-only RBAC are the trust anchors; the file is re-verified on every classloader
load to detect on-disk tampering.

---

## datasource_user_permissions

Grants a specific user access to a specific datasource with granular controls.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `datasource_id` | FK → `datasources` |
| `user_id` | FK → `users` |
| `can_read` | BOOLEAN DEFAULT false |
| `can_write` | BOOLEAN DEFAULT false |
| `can_ddl` | BOOLEAN DEFAULT false — CREATE/ALTER/DROP |
| `can_break_glass` | BOOLEAN NOT NULL DEFAULT false (AF-385, Flyway V94) — grants the emergency break-glass submission mode on this datasource. Required for everyone (including admins); time-boxed via `expires_at`. |
| `row_limit_override` | INTEGER nullable — overrides datasource default |
| `allowed_schemas` | TEXT[] — null means all schemas permitted |
| `allowed_tables` | TEXT[] — null means all tables permitted |
| `restricted_columns` | TEXT[] nullable — fully-qualified `schema.table.column` entries whose values are masked in SELECT results before persistence and surfaced to the AI analyzer; null/empty means no column restrictions. A column listed here with no matching `masking_policy` row uses the static `FULL` mask (`***`); a `masking_policy` for the same column overrides it with the configured strategy. |
| `expires_at` | TIMESTAMPTZ nullable — time-limited access grants |
| `created_by` | FK → `users` |
| `created_at` | TIMESTAMPTZ |

---

## masking_policy

Conditional / role-based dynamic data masking (AF-381). Each row binds a masking **strategy** to one
datasource column, with an optional **reveal condition** evaluated per query submitter. A submitter
whose role, one of whose group ids, or whose user id appears in any `reveal_to_*` column sees the
unmasked value; everyone else gets the strategy output. This *enhances* `restricted_columns` masking —
it governs *how* a visible value is rendered, applied at result-read time in the proxy **before
serialization and before the result snapshot is stored**, so unmasked values never persist. Created by
`V58__create_masking_policy.sql`.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `datasource_id` | FK → `datasources` |
| `column_ref` | TEXT — `schema.table.column` (matched with the same `schema.table.column` → `table.column` → bare-column precedence as `restricted_columns`) |
| `strategy` | ENUM `masking_strategy`: `FULL` \| `PARTIAL` \| `HASH` \| `EMAIL` \| `FORMAT_PRESERVING` |
| `strategy_params` | JSONB DEFAULT `'{}'` — strategy parameters, e.g. `{"visible_suffix": "4"}` for `PARTIAL` |
| `reveal_to_roles` | TEXT[] nullable — `user_role_type` values that see the unmasked value |
| `reveal_to_group_ids` | UUID[] nullable — user-group ids that see the unmasked value |
| `reveal_to_user_ids` | UUID[] nullable — individual user ids that see the unmasked value |
| `enabled` | BOOLEAN DEFAULT true — disabled policies are ignored during resolution |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Indexed by `(organization_id, datasource_id, enabled)` to back the per-execution resolution scan.

**`masking_strategy` values:** `FULL` (whole value → `***`, identical to legacy `restricted_columns`),
`PARTIAL` (keep the last N characters per `visible_suffix`, default 4), `HASH` (stable SHA-256 hex of
the value), `EMAIL` (`j***@domain` — preserve the first local-part character and the domain),
`FORMAT_PRESERVING` (preserve length/shape: digits and letters replaced, separators kept).

---

## row_security_policy

Per-table **row-level security** predicates (AF-380). Each row binds a structured predicate
(`column operator value`) to one datasource table, evaluated per query submitter so a scoped user
only **sees** (SELECT) or **affects** (UPDATE/DELETE) the rows they are authorised for. Enforcement
runs in the proxy at the **AST layer**: for each referenced policied table, the predicate is injected
as a security-barrier subquery (SELECT) or a `WHERE` conjunct (UPDATE/DELETE), with the comparison
value **bound as a JDBC parameter** — never string-concatenated. Created by
`V60__create_row_security_policy.sql`.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `datasource_id` | FK → `datasources` |
| `table_name` | TEXT — `schema.table` (or bare `table`); matched case-insensitively against parsed table references, schema-optional |
| `column_name` | TEXT — the column the predicate filters on |
| `operator` | ENUM `row_security_operator`: `EQUALS` \| `NOT_EQUALS` \| `LESS_THAN` \| `LESS_THAN_OR_EQUAL` \| `GREATER_THAN` \| `GREATER_THAN_OR_EQUAL` \| `IN` \| `NOT_IN` |
| `value_type` | ENUM `row_security_value_type`: `VARIABLE` \| `LITERAL` |
| `value_expression` | TEXT — for `VARIABLE`, a `user.<key>` reference (built-ins `user.id` / `user.email` / `user.role` / `user.groups`, or a `users.attributes` key); for `LITERAL`, the fixed value |
| `applies_to_roles` | TEXT[] nullable — `user_role_type` values the policy applies to |
| `applies_to_group_ids` | UUID[] nullable — user-group ids the policy applies to |
| `applies_to_user_ids` | UUID[] nullable — individual user ids the policy applies to |
| `enabled` | BOOLEAN DEFAULT true — disabled policies are ignored during resolution |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Indexed by `(organization_id, datasource_id, enabled)` to back the per-execution resolution scan.

**`applies_to_*` polarity (note the inversion vs. masking).** Where `masking_policy.reveal_to_*`
*exempts* the listed targets, `row_security_policy.applies_to_*` *applies* to them. All three
`applies_to_*` empty ⇒ the policy filters **every** submitter (governance-safe default); a non-empty
list narrows it to submitters whose role / group / user id matches. There is **no implicit ADMIN
bypass** — when `applies_to_*` are empty, admins are filtered too, exactly as masking masks admins
unless `reveal_to` lists them.

**Fail-closed.** When a `VARIABLE` cannot be resolved (a missing `users.attributes` key, or
`user.groups` for a user in no groups), the predicate collapses to an always-false `1=0`, so the
submitter sees nothing rather than everything. Query shapes the proxy cannot provably filter (a
policied table inside a `UNION`, a CTE, a sub-select, an `INSERT … SELECT`, or an `UPDATE … FROM` /
`DELETE … USING` join onto another policied table) are **rejected with HTTP 422**, never run
unfiltered. Applied policy ids ride on the `QUERY_EXECUTED` audit metadata
(`applied_row_security_policy_ids`) — no row data is stored.

---

## data_classification_tag

Data-classification tags on datasource tables/columns (AF-447). Each row binds one classification to
one object (a table, or a specific column) so a single column may carry several classes via several
rows. Tags drive automatic derivation of stricter handling: a column-level tag auto-applies a
`masking_policy`, the AI analyzer raises a query's risk score when it references a tagged table, and a
read-only derivation preview suggests a stricter review posture. Tags are immutable (create / delete
only) and queryable org-wide for compliance reporting. Created by
`V90__create_data_classification_tags.sql`.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` (`ON DELETE CASCADE`) |
| `datasource_id` | FK → `datasources` (`ON DELETE CASCADE` — tags are datasource-scoped child config) |
| `table_name` | TEXT NOT NULL — `schema.table` or bare `table` |
| `column_name` | TEXT nullable — the tagged column; **NULL = a table-level tag** (informational; derives no masking) |
| `classification` | ENUM `data_classification`: `PII` \| `PCI` \| `PHI` \| `GDPR` \| `FINANCIAL` \| `SENSITIVE` |
| `note` | TEXT nullable — optional free-text note |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Indexed by `(organization_id, datasource_id)` for the per-datasource scan and `(organization_id)` for
the org-wide reporting scan. A unique expression index on
`(organization_id, datasource_id, table_name, COALESCE(column_name, ''), classification)` rejects
duplicate tags — the `COALESCE` collapses NULL column names so duplicate table-level tags are caught
too.

**Derivation.** Each classification maps to a default masking strategy and review posture
(PII/GDPR/FINANCIAL → `PARTIAL`, PCI/PHI → `FULL` + 2 approvals, SENSITIVE → `HASH`). On creating a
column-level tag with `apply_masking` on, the service idempotently creates a `masking_policy` for
`table_name.column_name` (skipped when an enabled policy already covers it). **Deleting a tag does not
delete the derived masking policy.** Tag changes are audited via `DATA_CLASSIFICATION_TAG_ADDED` /
`DATA_CLASSIFICATION_TAG_REMOVED` (resource `data_classification_tag`).

---

## review_plans

Defines an approval policy. Assigned to datasources.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `name` | VARCHAR(255) |
| `description` | TEXT |
| `requires_ai_review` | BOOLEAN DEFAULT true |
| `requires_human_approval` | BOOLEAN DEFAULT true |
| `min_approvals_required` | INTEGER DEFAULT 1 |
| `approval_timeout_hours` | INTEGER DEFAULT 24 — see *Approval timeout* below |
| `auto_approve_reads` | BOOLEAN DEFAULT false — bypass review for SELECT |
| `notify_channels` | TEXT[] — values: `email` \| `slack` \| `webhook` |
| `created_at` | TIMESTAMPTZ |

### Approval timeout

`QueryTimeoutJob` (workflow module) scans every `accessflow.workflow.timeout-poll-interval`
(default 5 minutes). Any `query_requests` row in `PENDING_REVIEW` whose
`created_at + approval_timeout_hours` is in the past is auto-transitioned to the dedicated
`TIMED_OUT` terminal status and a `QueryTimedOutEvent` is published. **No `review_decisions` row
is inserted.** The status field is the authoritative signal for distinguishing auto-rejections from
manual rejections (manual rejections land in `REJECTED`); the audit listener still emits a
`QUERY_REJECTED` audit row with metadata `{auto_rejected: true, reason: "approval_timeout",
timeout_hours: N}` for backward compatibility with external audit consumers. The job uses
ShedLock-on-Redis so only one node in a cluster runs each tick. See
[05-backend.md → Scheduled jobs and clustering](05-backend.md#scheduled-jobs-and-clustering).

---

## review_plan_approvers

Maps users or roles to a review plan, with support for multi-stage sequential approval.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `review_plan_id` | FK → `review_plans` |
| `user_id` | FK → `users` nullable — specific user |
| `role` | ENUM: `ADMIN` \| `REVIEWER` — any user with this role can approve |
| `stage` | INTEGER — enables multi-stage sequential approval (stage 1 before stage 2) |

---

## routing_policy

Ordered, attribute-based **policy-as-code routing rules** (AF-379, Flyway `V59__create_routing_policy.sql`). Evaluated by the workflow state machine after AI analysis and before reviewer fan-out: the **first enabled policy by ascending `priority` whose `condition` matches** decides how the query is routed; on no match the query falls through to the datasource's review plan exactly as before. A policy with a null `datasource_id` is org-wide; otherwise it is scoped to that datasource.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` NOT NULL |
| `datasource_id` | FK → `datasources` NULL — null means org-wide; otherwise scopes the policy to one datasource |
| `name` | VARCHAR(255) NOT NULL |
| `description` | VARCHAR(2000) nullable |
| `priority` | INTEGER NOT NULL — evaluation order, lowest first; **UNIQUE per `organization_id`** |
| `enabled` | BOOLEAN NOT NULL DEFAULT true — disabled policies are skipped during evaluation |
| `condition` | JSONB NOT NULL DEFAULT `'{}'` — typed condition tree (wire format below) |
| `action` | ENUM `routing_action`: `AUTO_APPROVE` \| `AUTO_REJECT` \| `REQUIRE_APPROVALS` \| `ESCALATE` |
| `required_approvals` | INTEGER nullable — non-null only for `REQUIRE_APPROVALS` (absolute minimum) and `ESCALATE` (delta added to the review-plan minimum; default 1) |
| `reason` | VARCHAR(500) nullable — recorded on the resulting `routing_decision` and audit row |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

**Indexes**
- `(organization_id, enabled, priority)` — backs the per-submission ascending-priority evaluation scan.
- UNIQUE `(organization_id, priority)` — each priority is used at most once per org; the reorder API rewrites the full set atomically.

**`routing_action` values:** `AUTO_APPROVE` (short-circuit straight to `APPROVED`), `AUTO_REJECT` (short-circuit straight to `REJECTED` — a new `PENDING_AI → REJECTED` state-machine edge), `REQUIRE_APPROVALS` (force human review with an absolute minimum of `required_approvals` approvers), `ESCALATE` (force human review with effective minimum = the review plan's minimum + `required_approvals` delta, default delta 1).

### `condition` JSONB wire format

The condition is a polymorphic, `"type"`-discriminated tree (snake_case, no external policy engine, no raw SQL). Logical combinators nest arbitrarily; the UI's guided builder authors a single-level `and` / `or` of (optionally `not`-wrapped) leaf conditions, while API/bootstrap-authored policies may nest freely.

| `"type"` | Fields | Matches when |
|----------|--------|--------------|
| `and` | `children: []` | all children match |
| `or` | `children: []` | any child matches |
| `not` | `child` | the child does not match |
| `query_type` | `any_of: [QueryType]` | the query's type is in the set |
| `referenced_table` | `globs: [string]` | a referenced table matches a glob (e.g. `payroll.*`, `*.users`) |
| `risk_level` | `any_of: [RiskLevel]` | the AI risk level is in the set |
| `risk_score` | `operator` (`LT`/`LTE`/`GT`/`GTE`/`EQ`), `value` | the AI risk score satisfies the comparison |
| `requester_role` | `any_of: [Role]` | the submitter's role is in the set |
| `requester_group` | `group_ids: [uuid]` | the submitter belongs to one of the groups |
| `time_of_day` | `start_minute_of_day`, `end_minute_of_day` | the submission time (server local timezone) falls in the window; supports overnight wrap-around |
| `day_of_week` | `any_of: [DayOfWeek]` | the submission day is in the set |
| `has_where` | `expected: bool` | presence of a WHERE clause equals `expected` |
| `has_limit` | `expected: bool` | presence of a LIMIT clause equals `expected` |
| `transactional` | `expected: bool` | the `BEGIN…COMMIT` transactional flag equals `expected` |
| `source_ip` (AF-446) | `cidrs: [string]` | the submission source IP falls within any CIDR (IPv4 or IPv6). CIDR syntax is validated on create / update (422 on a malformed block). **Fails closed**: false when no source IP was captured |
| `user_agent` (AF-446) | `patterns: [string]` | the submission user-agent matches any glob (`*` wildcard, case-insensitive). **Fails closed**: false when no user-agent was captured |
| `time_since_last_approval` (AF-446) | `operator` (`LT`/`LTE`/`GT`/`GTE`/`EQ`), `minutes` | minutes since the requester's last APPROVED/EXECUTED query **on the same datasource** satisfy the comparison. **Fails closed**: false when the requester has no prior approval there |
| `cicd_origin` (AF-446) | `expected: bool` | whether the request came from a CI/CD pipeline (submitted via an API key or with the `X-AccessFlow-CI` header) equals `expected`. Deterministic — the flag defaults to `false` |
| `anomaly_detected` (AF-383) | `expected: bool` | whether the submitter currently has an `OPEN` `behavior_anomaly` on the target datasource equals `expected`. The UBA detector is a periodic batch over **past** data, so this signal escalates the flagged user's **next** query — pair it with `ESCALATE`. Deterministic — false when the user has no open anomaly there |

On the AI-skipped path (`datasource.ai_analysis_enabled=false`) the risk-based operands (`risk_level`, `risk_score`) evaluate to **false** — there is no AI signal. Routing is **not** run on the AI-failure path.

The client-context operands (`source_ip`, `user_agent`, `cicd_origin`, `time_since_last_approval`) are **fail-closed**: when the required signal is absent the leaf evaluates to `false`, so a permissive `AUTO_APPROVE` policy keyed on a positive match never fires on missing context. Express escalation of unknown context as `not(source_ip(...))` (true on a missing IP). The IP / user-agent / CI-CD flag are captured at submission and persisted on `query_requests` (`submitted_ip`, `submitted_user_agent`, `cicd_origin`) — routing runs asynchronously after AI completion, where no HTTP request exists.

Example:

```json
{
  "type": "and",
  "children": [
    { "type": "query_type", "any_of": ["DELETE"] },
    { "type": "referenced_table", "globs": ["payroll.*"] }
  ]
}
```

---

## routing_decision

Records the outcome of routing a single query request (AF-379, Flyway `V59__create_routing_policy.sql`). One row per query that reached the routing stage.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `query_request_id` | FK → `query_requests` NOT NULL — **UNIQUE** (one decision per query) |
| `matched_policy_id` | FK → `routing_policy` NULL, `ON DELETE SET NULL` — null when no policy matched (fall-through) or the policy was later deleted |
| `action` | ENUM `routing_action` — the action that fired |
| `effective_min_approvals` | INTEGER nullable — resolved absolute approver count for `ESCALATE` / `REQUIRE_APPROVALS`; read by the review service as the per-stage minimum override |
| `reason` | VARCHAR(500) nullable — copied from the matched policy |
| `created_at` | TIMESTAMPTZ |

---

## user_groups

Named, organisation-scoped collections of users. Groups are used as the indirection layer for reviewer assignment (see `datasource_reviewers`) and may be auto-synced from OAuth2 / SAML IdP claims.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `name` | VARCHAR(128) NOT NULL — unique per `(organization_id, lower(name))` |
| `description` | VARCHAR(512) NULL |
| `version` | BIGINT NOT NULL DEFAULT 0 — optimistic locking |
| `created_at`, `updated_at` | TIMESTAMPTZ |

## user_group_memberships

Composite-key join table that bundles users into groups.

| Column | Type / Notes |
|--------|-------------|
| `user_id` | FK → `users` ON DELETE CASCADE — part of PK |
| `group_id` | FK → `user_groups` ON DELETE CASCADE — part of PK |
| `source` | ENUM: `MANUAL` \| `IDP` — `IDP` rows are owned by the OAuth2 / SAML login sync flow; `MANUAL` rows are owned by admins via the API |
| `joined_at` | TIMESTAMPTZ |

The SSO group-sync flow replaces only `source = 'IDP'` rows per user on each login, leaving `source = 'MANUAL'` rows untouched.

---

## datasource_reviewers

Per-datasource reviewer assignment. Each row attaches **either** a user or a group to a datasource as an eligible reviewer. When a datasource has at least one row in this table, **only** listed reviewers (and members of listed groups) can see and decide its queries. Datasources with zero rows fall back to the existing plan-approver logic.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `datasource_id` | FK → `datasources` ON DELETE CASCADE |
| `user_id` | FK → `users` ON DELETE CASCADE — exactly one of `user_id` / `group_id` must be set (CHECK constraint) |
| `group_id` | FK → `user_groups` ON DELETE CASCADE |
| `created_by` | FK → `users` — admin who created the assignment |
| `created_at` | TIMESTAMPTZ |

Unique constraints: `(datasource_id, user_id)` where `user_id IS NOT NULL` and `(datasource_id, group_id)` where `group_id IS NOT NULL`.

---

## query_templates

Saved SQL snippets that analysts load into `/editor`. Pure save / load surface — submission still goes through `POST /api/v1/queries` (AI analysis + review). `:param` placeholders are stored verbatim and substituted client-side before submission.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` ON DELETE CASCADE |
| `owner_id` | FK → `users` ON DELETE CASCADE — the analyst who created the template |
| `datasource_id` | FK → `datasources` ON DELETE SET NULL — optional "pinned" datasource for which the template was authored |
| `name` | VARCHAR(128) NOT NULL |
| `body` | TEXT NOT NULL — raw SQL, may contain `:param` placeholders |
| `description` | VARCHAR(1000) nullable |
| `tags` | TEXT[] NOT NULL DEFAULT `ARRAY[]::TEXT[]` — free-form tags, capped at 10 per template, 32 chars each |
| `visibility` | ENUM `query_template_visibility`: `PRIVATE` (owner only) \| `TEAM` (every user in the org) |
| `version` | BIGINT NOT NULL DEFAULT 0 — JPA optimistic-locking version |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

Unique index `(organization_id, owner_id, LOWER(name))` — an owner may not have two templates with the same case-insensitive name, but two owners in the same org may. Filter indexes on `(organization_id, owner_id)`, `(organization_id, visibility)`, and `(organization_id, datasource_id) WHERE datasource_id IS NOT NULL`; GIN index on `tags` for tag filtering. Visibility enforcement (PRIVATE → owner only; TEAM → org-readable, owner-mutable) lives in `DefaultQueryTemplateService` — controllers do not implement it.

---

## query_template_versions

Immutable version history of saved query templates (AF-442). A snapshot is written on every content-changing save and on restore; rows are INSERT-only and never updated. `version_number` is contiguous per template starting at 1.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `template_id` | FK → `query_templates` ON DELETE CASCADE — deleting a template discards its history |
| `organization_id` | UUID NOT NULL — denormalised so version reads filter by org without joining the template |
| `version_number` | INTEGER NOT NULL — per-template sequence starting at 1 (read-then-increment, guarded by the unique index) |
| `datasource_id` | UUID nullable — the pinned datasource at snapshot time |
| `name` | VARCHAR(128) NOT NULL — snapshot of the template name |
| `body` | TEXT NOT NULL — snapshot of the SQL body |
| `description` | VARCHAR(1000) nullable |
| `tags` | TEXT[] NOT NULL DEFAULT `ARRAY[]::TEXT[]` |
| `visibility` | ENUM `query_template_visibility` — point-in-time visibility (**not** used for access control; the current template's visibility is) |
| `change_type` | ENUM `query_template_change_type`: `CREATED` \| `UPDATED` \| `RESTORED` |
| `author_id` | UUID NOT NULL — the user who triggered the save/restore. No FK: an audit-style immutable row must outlive user deletion |
| `created_at` | TIMESTAMPTZ |

Unique index `(template_id, version_number)` enforces contiguous, non-duplicated numbering and is the race safety-net for the `max + 1` numbering in `DefaultQueryTemplateVersioningService`. Filter index on `(template_id)` for the newest-first list. Snapshot writing, the no-op-on-unchanged check, and visibility-enforced reads live in `DefaultQueryTemplateVersioningService`; restore (which reuses the template's owner + name-uniqueness guards) lives in `DefaultQueryTemplateService`.

---

## query_snapshots

Immutable, sanitized snapshot of an **executed** query (AF-449). Exactly one row is written when a query transitions to `EXECUTED` — `DefaultQuerySnapshotService.recordOnExecution`, invoked by the workflow module's `@ApplicationModuleListener` on `QueryExecutedEvent` (only when `finalStatus = EXECUTED`; FAILED executions get no snapshot). Rows are INSERT-only and never updated. The snapshot is a forensic/compliance record **and** the exact-replay artifact for `POST /queries/{id}/replay` (see [docs/05-backend.md → "Query snapshots & replay"](05-backend.md)).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `query_request_id` | UUID NOT NULL **UNIQUE** FK → `query_requests` ON DELETE CASCADE. The UNIQUE constraint is the idempotency backstop for redelivered events (one snapshot per executed query) |
| `organization_id` | UUID NOT NULL — denormalised for org-scoped reads |
| `datasource_id` | UUID NOT NULL — the **source** datasource the query executed against |
| `submitted_by` | UUID NOT NULL — the original submitter. No FK: an immutable record must outlive user deletion (like `audit_log.actor_id`) |
| `sql_text` | TEXT NOT NULL — the exact SQL captured for replay (AccessFlow inlines literals into `sql_text`; there is no separate bound-parameter store, so this is the complete replay artifact) |
| `query_type` | ENUM `query_type` |
| `transactional` | BOOLEAN NOT NULL DEFAULT FALSE |
| `db_type` | ENUM `db_type` — the source engine; the replay gate requires the target datasource to match |
| `referenced_tables` | TEXT[] NOT NULL DEFAULT `ARRAY[]::TEXT[]` — tables the query touched (normalized `schema.table`/`table`), used by the replay schema-compatibility gate |
| `schema_hash` | VARCHAR(64) nullable — SHA-256 fingerprint of the source schema at execution time (forensic only; null when introspection was unavailable). Both source and target hashes are recorded in the replay audit row so drift is visible |
| `ai_analysis` | JSONB nullable — snapshot of the AI verdict at execution time (null when AI was skipped/absent) |
| `review_decisions` | JSONB NOT NULL DEFAULT `'[]'` — snapshot of the approval decisions at execution time |
| `rows_affected` | BIGINT nullable |
| `execution_duration_ms` | INTEGER nullable |
| `executed_at` | TIMESTAMPTZ NOT NULL |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT `CURRENT_TIMESTAMP` |

Filter index on `(organization_id)`. No special DB-role grant (a normal app-role table; only `audit_log` has the writer-role separation).

---

## query_requests

The central entity. Represents a single SQL submission through the platform.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `datasource_id` | FK → `datasources` |
| `submitted_by` | FK → `users` |
| `sql_text` | TEXT — the raw submitted SQL (including any `BEGIN; … COMMIT;` envelope, verbatim, for audit and AI prompting) |
| `query_type` | ENUM: `SELECT` \| `INSERT` \| `UPDATE` \| `DELETE` \| `DDL` \| `OTHER`. For a transactional submission, holds the *representative* type — i.e. the first inner statement (INSERT/UPDATE/DELETE) — so permission checks (`can_write`) and state-machine fast-path logic continue to work unchanged. |
| `transactional` | BOOLEAN NOT NULL DEFAULT FALSE — true when `sql_text` is a `BEGIN; … COMMIT;` envelope wrapping a homogeneous INSERT/UPDATE/DELETE batch. The executor re-parses `sql_text` at execute time to recover the individual statements and runs them inside a single JDBC transaction (`autoCommit=false` + sum of `executeLargeUpdate` + commit/rollback). `rows_affected` then holds the sum across inner statements. |
| `status` | ENUM: `PENDING_AI` \| `PENDING_REVIEW` \| `APPROVED` \| `REJECTED` \| `TIMED_OUT` \| `EXECUTED` \| `FAILED` \| `CANCELLED` |
| `submission_reason` | ENUM `submission_reason`: `USER_SUBMITTED` (default) \| `AI_SUGGESTION` (AF-451) \| `EMERGENCY_ACCESS` (AF-385, Flyway V93). `AI_SUGGESTION` marks a draft created by applying an AI optimization suggestion in the editor; `EMERGENCY_ACCESS` marks a query that bypassed pre-approval through the break-glass path. Recorded in the `QUERY_SUBMITTED` audit metadata. NOT NULL DEFAULT `'USER_SUBMITTED'`. |
| `justification` | TEXT nullable — requester's stated reason for the query |
| `ai_analysis_id` | FK → `ai_analyses` nullable |
| `execution_started_at` | TIMESTAMPTZ nullable |
| `execution_completed_at` | TIMESTAMPTZ nullable |
| `rows_affected` | BIGINT nullable |
| `error_message` | TEXT nullable |
| `execution_duration_ms` | INTEGER nullable |
| `scheduled_for` | TIMESTAMPTZ nullable — when set on submission, defers execution: once the query reaches `APPROVED`, the `ScheduledQueryRunJob` picks it up at `scheduled_for ≤ now()` and triggers execution via `QueryLifecycleService.executeScheduled`. A partial index `idx_query_requests_scheduled_for ON query_requests(scheduled_for) WHERE scheduled_for IS NOT NULL` keeps the scan cheap. |
| `previous_run_id` | UUID nullable, FK → `query_requests(id)`. Set on a successful execution (AF-361) when an earlier `EXECUTED` row exists for the same `(submitted_by, datasource_id, canonical_sql)`. Used by `GET /queries/{id}/diff` to compute the rows-affected / execution-ms / row-count deltas surfaced on `QueryDetailPage`. Rows that executed before the feature shipped have `canonical_sql = NULL` and never match — diff is unavailable for those queries. |
| `canonical_sql` | TEXT nullable — populated on each successful execution with the output of `SqlCanonicalizer.canonicalize(sql_text)` (strip comments, collapse whitespace, upper-case). Lookup key for `previous_run_id`. A partial index `idx_query_requests_diff_lookup ON query_requests(submitted_by, datasource_id, canonical_sql, execution_completed_at DESC) WHERE status = 'EXECUTED' AND canonical_sql IS NOT NULL` keeps the per-execution lookup a single indexed scan. |
| `submitted_ip` | VARCHAR(45) nullable (AF-446, Flyway `V88`) — source IP captured at submission (`X-Forwarded-For` first hop, else remote address). Read by the `source_ip` routing condition (routing runs asynchronously, after submission). |
| `submitted_user_agent` | TEXT nullable (AF-446, `V88`) — the submission `User-Agent` header. Read by the `user_agent` routing condition. |
| `cicd_origin` | BOOLEAN NOT NULL DEFAULT FALSE (AF-446, `V88`) — true when the query was submitted via an API key or with the `X-AccessFlow-CI` header. Read by the `cicd_origin` routing condition. |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

### Status Transitions

```
PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTED
                           ↘ REJECTED   (manual reviewer rejection)
                           ↘ TIMED_OUT  (approval-timeout auto-reject, see review_plans → Approval timeout)
           ↘ PENDING_REVIEW (if no AI)
           ↘ APPROVED       (routing policy AUTO_APPROVE — see routing_policy)
           ↘ REJECTED       (routing policy AUTO_REJECT — see routing_policy)
PENDING_REVIEW → CANCELLED (by submitter)
APPROVED       → CANCELLED (submitter, when scheduled_for is set and run hasn't fired yet)
APPROVED       → EXECUTED  (ScheduledQueryRunJob at scheduled_for ≤ now())
APPROVED       → FAILED    (on execution error)
```

**Auto-approve fast path (`PENDING_AI → APPROVED` directly).** When the datasource's review plan has `auto_approve_reads=true`, a SELECT whose AI analysis returns LOW or MEDIUM risk skips `PENDING_REVIEW` entirely. HIGH/CRITICAL risk SELECTs and all non-SELECT queries still go through human review. Plans with `requires_human_approval=false` always auto-approve on AI completion. AI failure (`AiAnalysisFailedEvent`) never auto-approves — the query always lands in `PENDING_REVIEW` so a human can inspect.

---

## ai_analyses

Stores the result of an AI analysis run for a query request.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `query_request_id` | FK → `query_requests` |
| `ai_provider` | ENUM: `OPENAI` \| `ANTHROPIC` \| `OLLAMA` \| `OPENAI_COMPATIBLE` \| `HUGGING_FACE` |
| `ai_model` | VARCHAR(100) — e.g. `claude-sonnet-4-20250514`, `gpt-4o` |
| `risk_score` | INTEGER 0–100 |
| `risk_level` | ENUM: `LOW` \| `MEDIUM` \| `HIGH` \| `CRITICAL` |
| `summary` | TEXT — short human-readable analysis summary |
| `issues` | JSONB — array of `{ severity, category, message, suggestion }` |
| `optimizations` | JSONB DEFAULT `'[]'` (AF-451) — array of `{ type (`INDEX`\|`REWRITE`), title, rationale, sql }`. Concrete, dialect-aware optimization suggestions; `sql` is a ready-to-run index DDL or rewritten query the editor can "Apply as draft". Empty array when none. |
| `missing_indexes_detected` | BOOLEAN |
| `affects_row_estimate` | BIGINT nullable — estimated rows impacted |
| `prompt_tokens` | INTEGER — summed across all participating models when orchestration is enabled (AF-450) |
| `completion_tokens` | INTEGER — summed across all participating models when orchestration is enabled (AF-450) |
| `failed` | BOOLEAN DEFAULT false — `true` when the AI provider call failed and the row is a sentinel placeholder (per AF-249). The detail / list APIs surface this flag so the frontend can render an "AI analysis failed" state instead of treating the sentinel `risk_level=CRITICAL` as a real risk verdict. |
| `error_message` | TEXT nullable — the analyzer failure reason when `failed=true`. Mirrors the `reason` field of `AiAnalysisFailedEvent`. Null on successful analyses. |
| `created_at` | TIMESTAMPTZ |

### `issues` JSONB Structure

```json
[
  {
    "severity": "HIGH",
    "category": "MISSING_WHERE_CLAUSE",
    "message": "UPDATE without WHERE clause will affect all rows",
    "suggestion": "Add a WHERE clause to limit the scope of the update"
  },
  {
    "severity": "MEDIUM",
    "category": "MISSING_INDEX",
    "message": "Column 'email' in WHERE clause has no index",
    "suggestion": "CREATE INDEX idx_users_email ON users(email)"
  }
]
```

---

## ai_config

Per-organization AI provider configurations. Many rows per organization — admins create
as many configurations as they need, and each datasource binds to a single configuration via
`datasources.ai_config_id`. The active `AiAnalyzerStrategy` delegate is built on demand by
`AiAnalyzerStrategyHolder` from the bound row; changes are picked up at runtime via an
`AiConfigUpdatedEvent` / `AiConfigDeletedEvent`. See [docs/05-backend.md → "AI Query
Analyzer Service"](05-backend.md#ai-query-analyzer-service).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` (not unique — many configs per org) |
| `name` | VARCHAR(255) — display name; `(organization_id, lower(name))` is UNIQUE |
| `provider` | ENUM `ai_provider`: `OPENAI` \| `ANTHROPIC` \| `OLLAMA` \| `OPENAI_COMPATIBLE` \| `HUGGING_FACE` |
| `model` | VARCHAR(100) — provider-specific model name |
| `endpoint` | VARCHAR(500) nullable — base URL. Honored at runtime when `provider = OLLAMA`, `OPENAI_COMPATIBLE`, or `HUGGING_FACE` (**required** for `OPENAI_COMPATIBLE`, which has no built-in default; optional for `OLLAMA` and `HUGGING_FACE`, which fall back to `http://localhost:11434` and `https://router.huggingface.co/v1` respectively); ignored for OpenAI and Anthropic (Spring AI's built-in default endpoints are used). The column remains nullable for back-compat — pre-existing values on OpenAI/Anthropic rows are preserved on the wire but have no runtime effect. |
| `api_key_encrypted` | TEXT nullable — AES-256-GCM ciphertext; `@JsonIgnore` |
| `timeout_ms` | INTEGER — call timeout, CHECK 1000–600000 |
| `max_prompt_tokens` | INTEGER — CHECK 100–200000 |
| `max_completion_tokens` | INTEGER — CHECK 100–200000 |
| `system_prompt_template` | TEXT nullable — admin-editable analyzer prompt override. `NULL`/blank means "use the built-in default". A custom value must contain the `{{sql}}` placeholder (other tokens — `{{schema_context}}`, `{{db_type}}`, `{{language}}` — are optional) and is substituted at render time. Editing it evicts the cached delegate via `AiConfigUpdatedEvent`. Max 20,000 chars. |
| `langfuse_prompt_name` | VARCHAR(255) nullable — when set **and** the org's `langfuse_config` has `prompt_management_enabled`, the analyzer fetches its system prompt from Langfuse by this name at render time (falling back to `system_prompt_template` / the built-in default on miss). `NULL` = do not use Langfuse for this config. |
| `langfuse_prompt_label` | VARCHAR(255) nullable — Langfuse label/version selector for `langfuse_prompt_name` (defaults to `production` when a name is set with no label). Cleared automatically when the name is cleared. |
| `rag_enabled` | BOOLEAN DEFAULT false — when true, RAG retrieval augments analysis / text-to-SQL for this config (AF-336). The remaining `rag_*` / `embedding_*` columns are validated only when this is true. |
| `rag_store_type` | ENUM `rag_store_type`: `PGVECTOR` (in-app, shared Postgres + `vector` extension) \| `QDRANT` (external). Nullable; required when `rag_enabled`. |
| `rag_top_k` | INTEGER DEFAULT 4 — number of chunks retrieved per query, CHECK 1–20. |
| `rag_similarity_threshold` | DOUBLE PRECISION DEFAULT 0.5 — minimum cosine similarity, CHECK 0–1. |
| `rag_endpoint` | VARCHAR(500) nullable — external store endpoint (QDRANT host[:port] or URL). Required for `QDRANT`. |
| `rag_collection` | VARCHAR(255) nullable — external collection/index name. Required for `QDRANT`. |
| `rag_api_key_encrypted` | TEXT nullable — AES-256-GCM ciphertext for the external store API key; `@JsonIgnore`. |
| `embedding_provider` | ENUM `ai_provider` nullable — dedicated embedding provider, independent of the chat `provider`. `ANTHROPIC` is rejected (no embeddings API). Required when `rag_enabled`. |
| `embedding_model` | VARCHAR(100) nullable — embedding model name. Required when `rag_enabled`. |
| `embedding_endpoint` | VARCHAR(500) nullable — custom embedding base URL (OLLAMA / OPENAI_COMPATIBLE / HUGGING_FACE). |
| `embedding_api_key_encrypted` | TEXT nullable — AES-256-GCM ciphertext for the embedding provider key; `@JsonIgnore`. |
| `orchestration_enabled` | BOOLEAN DEFAULT false (AF-450) — when true, the primary model votes alongside the enabled `ai_config_model` members; analysis fans out in parallel and aggregates. |
| `voting_strategy` | ENUM `voting_strategy`: `WEIGHTED_AVERAGE` (default) \| `MAX_RISK` \| `MAJORITY` (AF-450). How members' risk verdicts combine. |
| `voting_weight` | DOUBLE PRECISION DEFAULT 1.0, CHECK > 0 (AF-450) — the primary model's weight in the vote. |
| `guardrail_patterns` | JSONB DEFAULT `'[]'` (AF-450) — array of case-insensitive regex strings; a submitted SQL / NL prompt matching any is blocked before the model call (HTTP 422 `AI_GUARDRAIL_BLOCKED`). Empty = guardrails off. Each pattern must compile as a regex (else HTTP 400 `AI_CONFIG_ORCHESTRATION_INVALID`). |
| `version` | BIGINT — optimistic locking |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

`OPENAI_COMPATIBLE` targets any OpenAI API–compatible backend (vLLM, LM Studio, Together, Groq,
OpenRouter, …): it reuses the OpenAI Spring AI client against the configured `endpoint` and may run
keyless (`api_key_encrypted` null) for self-hosted servers that need no auth. Creating or updating a
row with `provider = OPENAI_COMPATIBLE` and a blank `endpoint` is rejected (HTTP 400
`AI_CONFIG_ENDPOINT_REQUIRED`).

`HUGGING_FACE` also reuses the OpenAI Spring AI client (Hugging Face speaks the OpenAI-compatible
`/v1/chat/completions` wire format) and is keyless-capable. It defaults `endpoint` to the hosted
Inference Providers router (`https://router.huggingface.co/v1`, authenticated with a HF token) and
accepts a custom base URL to target a **local / self-hosted Text Generation Inference (TGI ≥ 1.4)**
server (e.g. `http://localhost:3000/v1`, tokenless) or a Dedicated Inference Endpoint. Unlike
`OPENAI_COMPATIBLE`, a blank `endpoint` is accepted (the router default applies).

Deletion is rejected (HTTP 409 `AI_CONFIG_IN_USE`) while any datasource still references the
row. Unbind first (by switching the datasource to a different config or disabling
`ai_analysis_enabled`) before deleting.

Invalid RAG settings on create/update are rejected with HTTP 400 `RAG_CONFIG_INVALID` (e.g. RAG
enabled without a store type or embedding model, an `ANTHROPIC` embedding provider, or a `QDRANT`
backend missing its endpoint/collection).

Invalid orchestration / guardrail settings are rejected with HTTP 400
`AI_CONFIG_ORCHESTRATION_INVALID` (a guardrail pattern that is not a valid regex, a non-positive
voting weight, an orchestration member missing its provider/model, or an `OPENAI_COMPATIBLE` member
with no endpoint).

---

## ai_config_model

Additional orchestration members of a parent `ai_config` (AF-450). Each carries its own
provider/model/endpoint/key + voting weight and inherits the parent's `timeout_ms`,
`max_completion_tokens`, prompt template and RAG retriever — only the model varies. Members are
managed inline through the parent's create/update payload (full-replace, id-matched to preserve a
masked key).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `ai_config_id` | FK → `ai_config(id)` NOT NULL, ON DELETE CASCADE |
| `provider` | ENUM `ai_provider` |
| `model` | VARCHAR(100) |
| `endpoint` | VARCHAR(500) nullable — required for an `OPENAI_COMPATIBLE` member |
| `api_key_encrypted` | TEXT nullable — AES-256-GCM ciphertext; `@JsonIgnore` |
| `weight` | DOUBLE PRECISION DEFAULT 1.0, CHECK > 0 — this member's weight in the vote |
| `enabled` | BOOLEAN DEFAULT true — disabled members are skipped at analysis time |
| `sort_order` | INTEGER DEFAULT 0 — display / iteration order |
| `created_at` | TIMESTAMPTZ |

---

## ai_analysis_model_result

Per-model breakdown of a single `ai_analyses` row (AF-450) — one row per participating model, written
for **every** analysis (single-model configs get exactly one). Powers the admin dashboard's per-model
cost / latency view.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `ai_analysis_id` | FK → `ai_analyses(id)` NOT NULL, ON DELETE CASCADE |
| `ai_provider` | ENUM `ai_provider` |
| `ai_model` | VARCHAR(100) |
| `risk_score` | INTEGER 0–100 nullable — null when this member failed |
| `risk_level` | ENUM `risk_level` nullable — null when this member failed |
| `weight` | DOUBLE PRECISION DEFAULT 1.0 — the member's voting weight at analysis time |
| `prompt_tokens` | INTEGER DEFAULT 0 |
| `completion_tokens` | INTEGER DEFAULT 0 |
| `latency_ms` | BIGINT DEFAULT 0 — wall-clock of this member's provider call |
| `failed` | BOOLEAN DEFAULT false — true when this member's call/parse failed (others may still have succeeded) |
| `error_message` | TEXT nullable — the member's failure reason when `failed=true` |
| `created_at` | TIMESTAMPTZ |

---

## behavior_baseline

Rolling per-`(organization, user, datasource)` behavioural baseline for user-behaviour analytics
(UBA, AF-383). One row per principal/datasource pair; the `ai` module's `BehaviorAnomalyDetectionJob`
upserts it each cycle from `audit_log` **metadata only** (never query result data). The `features`
blob holds the rolling per-feature observation windows (capped at `accessflow.ai.anomaly.max-baseline-samples`),
the 24-bucket active-hour histogram used for off-hours detection, and the query-type / table
frequency maps used for categorical-novelty and unseen-table detection. Created by
`V92__create_behavior_baselines_and_anomalies.sql`.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `user_id` | FK → `users` — the principal the baseline profiles |
| `datasource_id` | FK → `datasources` — the datasource the baseline is scoped to |
| `features` | JSONB NOT NULL DEFAULT `'{}'` — rolling per-feature observation windows (query count, distinct tables, rows returned, error rate), the 24-bucket active-hour histogram, and the query-type / table frequency maps |
| `sample_size` | INTEGER NOT NULL DEFAULT 0 — number of windows aggregated into the baseline; detection stays dormant until it reaches `accessflow.ai.anomaly.min-sample-size` (cold-start guard) |
| `last_window_start` | TIMESTAMPTZ nullable — start of the most recently aggregated lookback window; the watermark the job advances from |
| `version` | BIGINT — optimistic locking |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Unique index on `(organization_id, user_id, datasource_id)` — one baseline per principal/datasource.

---

## behavior_anomaly

A single flagged out-of-pattern event (UBA, AF-383). Inserted by `BehaviorAnomalyDetectionJob` when a
feature's observed value crosses the configured z-score threshold (with an IQR robust fallback and a
constant-baseline guard) against the principal's `behavior_baseline`. Rows are immutable except for the
acknowledge / dismiss status transition. Created by `V92__create_behavior_baselines_and_anomalies.sql`.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `user_id` | FK → `users` — the principal whose activity was flagged |
| `datasource_id` | FK → `datasources` — the datasource the anomalous activity targeted |
| `feature` | TEXT NOT NULL — the tracked feature that triggered the anomaly (`query_count`, `active_hour`, `distinct_tables`, `query_types`, `new_tables`, `rows_returned`, `error_rate`) |
| `score` | DOUBLE PRECISION NOT NULL — the anomaly score (z-score, or the IQR-derived score on the robust fallback) |
| `observed_value` | DOUBLE PRECISION nullable — the observed feature value in the flagged window |
| `baseline_mean` | DOUBLE PRECISION nullable — the baseline mean for the feature at detection time |
| `baseline_stddev` | DOUBLE PRECISION nullable — the baseline standard deviation for the feature at detection time |
| `detail` | JSONB NOT NULL DEFAULT `'{}'` — structured per-feature evidence (e.g. the off-hours bucket, the unseen tables / query types, the contributing window counts) |
| `ai_summary` | TEXT nullable — optional AI-generated natural-language explanation of why the event is anomalous (null when `accessflow.ai.anomaly.summary-enabled=false` or the summary call failed — fully fail-safe, never blocks detection) |
| `status` | ENUM `behavior_anomaly_status`: `OPEN` \| `ACKNOWLEDGED` \| `DISMISSED` — defaults `OPEN` |
| `detected_at` | TIMESTAMPTZ NOT NULL — when the job flagged the event |
| `acknowledged_by` | FK → `users` nullable — the admin who acknowledged / dismissed the anomaly |
| `acknowledged_at` | TIMESTAMPTZ nullable |
| `window_start` / `window_end` | TIMESTAMPTZ — the lookback window the anomaly was detected over |
| `version` | BIGINT — optimistic locking |

**Indexes**
- `(organization_id, status, detected_at DESC)` — the admin list view (status filter, newest first).
- `(organization_id, user_id, datasource_id, status)` — the per-principal / per-datasource badge lookup and the `anomalyActive` routing signal.
- UNIQUE `(organization_id, user_id, datasource_id, feature, window_start)` — dedup so a re-run over the same window never double-inserts the same anomaly.

**`behavior_anomaly_status` values:** `OPEN` (just detected, raises the `anomalyActive` routing signal
and the badge count), `ACKNOWLEDGED` (an admin has triaged it; no longer raises the routing signal),
`DISMISSED` (an admin marked it a false positive).

---

## break_glass_events

Mandatory retrospective review opened by a break-glass / emergency-access execution (AF-385, Flyway
V95). One row per break-glass query. Owned by the `workflow` module (`workflow/internal/persistence/`);
cross-aggregate references are stored as bare UUIDs (no FK) like `query_snapshots` / `audit_log`, so
deleting a user never erases the forensic record. The executed query lands in its normal terminal
`EXECUTED`/`FAILED` state and is never re-opened — this row tracks the review alongside it.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `query_request_id` | UUID NOT NULL `UNIQUE` FK → `query_requests(id)` ON DELETE CASCADE — one event per query (idempotency backstop) |
| `organization_id` | UUID NOT NULL — bare (no FK) |
| `datasource_id` | UUID NOT NULL — bare (no FK) |
| `submitted_by` | UUID NOT NULL — bare (no FK); the user who broke glass |
| `justification` | TEXT NOT NULL — mandatory reason captured at submission |
| `status` | ENUM `break_glass_status`: `PENDING_REVIEW` (default) \| `REVIEWED` |
| `reviewed_by` | UUID nullable — bare (no FK); the admin who acknowledged (never the submitter) |
| `review_comment` | TEXT nullable — optional reconciliation note |
| `reviewed_at` | TIMESTAMPTZ nullable |
| `version` | BIGINT — optimistic lock |
| `created_at` | TIMESTAMPTZ DEFAULT now() |

Indexes: `UNIQUE(query_request_id)`; `(organization_id, status, created_at DESC)` for the admin
"Break-glass log" (status-filtered, newest first).

**`break_glass_status` values:** `PENDING_REVIEW` (unreconciled — surfaced on the admin log),
`REVIEWED` (an admin has acknowledged the emergency execution after the fact).

---

## push_subscriptions

Per-user, per-device W3C Push API subscriptions for the mobile/PWA one-tap approve/reject flow
(AF-444, Flyway V96). A user may hold several rows (one per browser/device). Owned by the
`notifications` module. The `endpoint + p256dh_key + auth_key` tuple is what `WebPushSender` needs to
deliver an encrypted push; these are device push keys (not AccessFlow credentials) and are stored in
clear — a DB read already exposes far more, and the self-approval / step-up guards live in the app.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `user_id` | UUID NOT NULL FK → `users(id)` ON DELETE CASCADE |
| `organization_id` | UUID NOT NULL FK → `organizations(id)` ON DELETE CASCADE |
| `endpoint` | TEXT NOT NULL `UNIQUE` — the push-service URL; upsert key (re-subscribing the same browser updates the row) |
| `p256dh_key` | TEXT NOT NULL — subscription public key (base64url) |
| `auth_key` | TEXT NOT NULL — subscription auth secret (base64url) |
| `user_agent` | TEXT nullable — display/diagnostics only |
| `created_at` | TIMESTAMPTZ DEFAULT now() |
| `last_used_at` | TIMESTAMPTZ nullable |

Indexes: `UNIQUE(endpoint)`; `(user_id)`; `(organization_id)`. A `404`/`410` from the push service
prunes the row (the subscription expired).

## dashboard_suggestion_state

Per-item lifecycle override for a personalized-dashboard AI optimization suggestion (AF-498, Flyway
V98). Owned by the `dashboard` module. A suggestion is implicitly `OPEN`; a row exists **only** when the
user diverged from that default (DISMISSED or APPLIED) for a specific `(ai_analysis_id, suggestion_index)`.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | UUID NOT NULL FK → `organizations(id)` ON DELETE CASCADE |
| `user_id` | UUID NOT NULL FK → `users(id)` ON DELETE CASCADE |
| `ai_analysis_id` | UUID NOT NULL FK → `ai_analyses(id)` ON DELETE CASCADE |
| `suggestion_index` | INTEGER NOT NULL — index into the analysis's `optimizations[]` array |
| `status` | ENUM `dashboard_suggestion_status`: `OPEN` \| `APPLIED` \| `DISMISSED` |
| `created_at` / `updated_at` | TIMESTAMPTZ DEFAULT now() |
| `version` | BIGINT — optimistic lock |

Indexes: `UNIQUE(organization_id, user_id, ai_analysis_id, suggestion_index)`. The enum type is named
`dashboard_suggestion_status` (distinct from the table name — in Postgres a table and a type share one
namespace).

## dashboard_digest_subscription

A user's opt-in for the scheduled weekly-digest email (AF-498, Flyway V98). One row per user. Owned by
the `dashboard` module; `WeeklyDigestJob` stamps `last_sent_at` so the digest fires at most once per the
configured period regardless of poll cadence or restarts.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `user_id` | UUID NOT NULL `UNIQUE` FK → `users(id)` ON DELETE CASCADE |
| `organization_id` | UUID NOT NULL FK → `organizations(id)` ON DELETE CASCADE |
| `enabled` | BOOLEAN NOT NULL DEFAULT false |
| `last_sent_at` | TIMESTAMPTZ nullable |
| `created_at` / `updated_at` | TIMESTAMPTZ DEFAULT now() |
| `version` | BIGINT — optimistic lock |

Indexes: `UNIQUE(user_id)`; partial `(last_sent_at) WHERE enabled = true` for the job's "due" scan.

## push_vapid_config

The single deployment-level VAPID keypair used to sign Web Push requests (AF-444, Flyway V96). One
row. Auto-generated and persisted on first use (mirrors `saml_config`) unless the
`ACCESSFLOW_PUSH_VAPID_*` env overrides are set. The public key is exposed to browsers via
`GET /push/vapid-public-key`; the private key is AES-256-GCM encrypted with `ENCRYPTION_KEY`.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `public_key` | TEXT NOT NULL — base64url uncompressed P-256 point |
| `private_key_encrypted` | TEXT NOT NULL — AES-256-GCM ciphertext of the base64url private scalar (`@JsonIgnore`) |
| `subject` | TEXT NOT NULL — VAPID `sub` claim (`mailto:` / `https:` contact URL) |
| `created_at` | TIMESTAMPTZ DEFAULT now() |

---

## knowledge_document

RAG knowledge-base documents attached to a RAG-enabled `ai_config` (AF-336). The raw `content` is
the admin-managed source of truth; on ingestion it is chunked, embedded with the config's embedding
model, and upserted into the configured vector store. Deleting a row removes its stored chunks.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `ai_config_id` | FK → `ai_config(id)` NOT NULL, ON DELETE CASCADE |
| `organization_id` | FK → `organizations` NOT NULL |
| `title` | VARCHAR(255) NOT NULL |
| `content` | TEXT NOT NULL — capped by `ACCESSFLOW_RAG_MAX_DOCUMENT_CHARS` (default 100,000) |
| `char_count` | INTEGER NOT NULL |
| `chunk_count` | INTEGER NOT NULL — number of embedded chunks produced |
| `status` | VARCHAR(20) — `INDEXED` \| `FAILED` |
| `error_message` | TEXT nullable |
| `version` | BIGINT — optimistic locking |
| `created_at` / `updated_at` | TIMESTAMPTZ |

## vector_store

Spring AI `PgVectorStore` table for the in-app (`PGVECTOR`) backend. Created by Flyway V69 with
`initializeSchema=false`; the `vector` extension itself is provisioned by a superuser init script
(`deploy/postgres-init/02-pgvector.sql` / the Helm initContainer / Testcontainers init), **not** by
Flyway. The embedding dimension is a Flyway placeholder (`ACCESSFLOW_RAG_PGVECTOR_DIMENSIONS`,
default 1536). Rows are partitioned per config via an `ai_config_id` metadata entry. The `QDRANT`
backend stores vectors externally instead of here.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK DEFAULT `gen_random_uuid()` |
| `content` | TEXT — chunk text |
| `metadata` | JSON — `{ai_config_id, document_id, organization_id, title}` |
| `embedding` | `vector(N)` — N = `ACCESSFLOW_RAG_PGVECTOR_DIMENSIONS`; HNSW cosine index |

---

## review_decisions

Records a reviewer's decision on a query request.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `query_request_id` | FK → `query_requests` |
| `reviewer_id` | FK → `users` |
| `decision` | ENUM: `APPROVED` \| `REJECTED` \| `REQUESTED_CHANGES` |
| `comment` | TEXT nullable |
| `stage` | INTEGER — which stage of multi-stage plan this decision belongs to |
| `decided_at` | TIMESTAMPTZ |

A unique index on `(query_request_id, reviewer_id, stage)` (Flyway V11) enforces single-decision-per-stage at the database level — a reviewer cannot record two decisions for the same query at the same stage. The service layer translates a duplicate insert attempt into an idempotent replay, returning the existing decision unchanged.

---

## query_comments

Inline collaboration comment threads anchored to a line range of a query's SQL while it is in a
co-authorable state (AF-441, Flyway V86). A thread is a root comment (`parent_comment_id IS NULL`) plus
replies pointing at the root. Owned by the `workflow` module; cross-aggregate references are bare UUIDs
(no JPA relationship across the module boundary). The live co-editing buffer is an ephemeral Yjs CRDT
relayed over `/ws` and is **not** stored here — only the durable discussion is.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `query_request_id` | UUID FK → `query_requests` (`ON DELETE CASCADE`) |
| `author_id` | UUID FK → `users` |
| `parent_comment_id` | UUID nullable, self-FK (`ON DELETE CASCADE`) — root thread = null; replies point at the root |
| `anchor_start_line` / `anchor_end_line` | INTEGER — 1-based line range of the anchored SQL. `CHECK (anchor_end_line >= anchor_start_line)`, `CHECK (anchor_start_line >= 1)` |
| `anchor_snapshot` | TEXT nullable — the anchored SQL text at creation, so the thread stays meaningful after the buffer is resubmitted |
| `body` | TEXT — the comment text |
| `status` | ENUM `comment_status`: `OPEN` \| `RESOLVED` (meaningful only on the root) |
| `resolved_by` | UUID nullable FK → `users` |
| `resolved_at` | TIMESTAMPTZ nullable |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Indexes on `(query_request_id, status)` and `(query_request_id, parent_comment_id)`, plus a partial index
on `(parent_comment_id) WHERE parent_comment_id IS NOT NULL`. Authorization (submitter / eligible reviewer
/ admin) and audit (`QUERY_COMMENT_*` actions) live in the service layer.

---

## access_grant_request

Just-in-time (JIT) time-bound access request (AF-378, Flyway V56). A user self-requests temporary, scoped access to a datasource; on final-stage approval the `access` module materialises a time-boxed `datasource_user_permissions` row and `AccessGrantExpiryJob` revokes it on expiry. Owned by the `access` module; cross-aggregate references are stored as bare UUIDs (no JPA relationship across the module boundary).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | UUID FK → `organizations` |
| `requester_id` | UUID FK → `users` |
| `datasource_id` | UUID FK → `datasources` |
| `can_read` / `can_write` / `can_ddl` | BOOLEAN — requested capabilities. `CHECK (can_read OR can_write OR can_ddl)` |
| `allowed_schemas` / `allowed_tables` | TEXT[] nullable — optional scope (null = all) |
| `requested_duration` | TEXT — ISO-8601 period (e.g. `PT4H`, `P1D`); bounded by `accessflow.access.min-duration`/`max-duration` |
| `justification` | TEXT |
| `status` | ENUM `access_grant_status`: `PENDING` \| `APPROVED` \| `REJECTED` \| `EXPIRED` \| `REVOKED` \| `CANCELLED` |
| `expires_at` | TIMESTAMPTZ nullable — set to `now + requested_duration` on grant |
| `granted_permission_id` | UUID nullable — id of the materialised `datasource_user_permissions` row. Bare UUID (no FK; the permission is hard-deleted on revoke), mirroring the `ai_analysis_id` convention |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Status transitions: `PENDING → APPROVED` (final-stage approval, materialises the permission) `→ EXPIRED` (job) or `→ REVOKED` (admin early-revoke); `PENDING → REJECTED` (reviewer) or `→ CANCELLED` (requester). A partial index on `(expires_at) WHERE status = 'APPROVED'` backs the expiry scan.

---

## access_grant_decision

Per-stage reviewer decisions on an access request, mirroring `review_decisions` for the multi-stage approval chain (Flyway V57). Reuses the existing `decision` PG enum.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `access_grant_request_id` | FK → `access_grant_request` (`ON DELETE CASCADE`) |
| `reviewer_id` | UUID FK → `users` |
| `decision` | ENUM `decision`: `APPROVED` \| `REJECTED` \| `REQUESTED_CHANGES` (only APPROVED/REJECTED used) |
| `stage` | INTEGER — which stage of the datasource's review plan |
| `comment` | TEXT nullable |
| `decided_at` | TIMESTAMPTZ |

A unique index on `(access_grant_request_id, reviewer_id, stage)` enforces single-decision-per-stage and drives idempotent-replay handling, exactly as `review_decisions` does for query review.

---

## audit_log

Append-only tamper-evident log of every meaningful action in the system. **No query result data is stored here — metadata only.**

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | UUID — references `organizations(id)` semantically; the SQL FK was dropped in V14 so audit history survives org deletion and the entity can live in the `audit` module without cross-module JPA joins |
| `actor_id` | UUID — references `users(id)` semantically; the SQL FK was dropped in V14. NULL for system-generated rows |
| `action` | VARCHAR(100) — e.g. `QUERY_SUBMITTED`, `QUERY_APPROVED`, `DATASOURCE_CREATED` |
| `resource_type` | VARCHAR(100) — e.g. `query_request`, `datasource`, `user` |
| `resource_id` | UUID |
| `metadata` | JSONB — context-specific details (no query result data) |
| `ip_address` | INET |
| `user_agent` | TEXT |
| `created_at` | TIMESTAMPTZ |
| `previous_hash` | BYTEA — HMAC-SHA256 of the immediately preceding row in the same org's chain (NULL for the anchor row and for any row written before V26) |
| `current_hash` | BYTEA — HMAC-SHA256(key, canonical(row) ‖ previous_hash). NULL only for rows written before V26 (skipped by the verifier) |

The hash chain (added in V26) is per organization. Inserts are serialized by a Postgres advisory lock keyed on the org id so each row deterministically chains to the prior one. The verifier (`GET /admin/audit-log/verify`) walks the chain in `(created_at ASC, id ASC)` order and returns the first row whose recomputed `current_hash` or recorded `previous_hash` does not match. Rows persisted before V26 keep NULL hashes and are treated as "pre-chain" — the verifier skips them up to the first chained row.

### Audit Action Types

| Action | Trigger |
|--------|---------|
| `QUERY_SUBMITTED` | User submits a query |
| `QUERY_AI_ANALYZED` | AI analysis completes successfully |
| `QUERY_AI_FAILED` | AI analysis errors (model timeout, malformed JSON, etc.) — extension to the original catalog so the read API can filter without parsing metadata |
| `QUERY_REVIEW_REQUESTED` | Query enters pending review |
| `QUERY_APPROVED` | Reviewer approves |
| `QUERY_REJECTED` | Reviewer rejects |
| `QUERY_EXECUTED` | Proxy executes approved query. Metadata is enriched (AF-383) with `datasource_id`, `query_type`, `referenced_tables`, `distinct_table_count`, and `rows_returned` so the UBA behavioural baselines (`behavior_baseline`) are derivable from `audit_log` alone — no query result data. Also carries `applied_masking_policy_ids` / `applied_row_security_policy_ids` when policies fired. |
| `QUERY_BREAK_GLASS_EXECUTED` | Proxy executes a break-glass / emergency-access query (AF-385), bypassing pre-approval. Prominently distinct from `QUERY_EXECUTED`; metadata carries `break_glass=true` plus the same UBA enrichment. Resource: `query_request`. |
| `BREAK_GLASS_REVIEWED` | An admin acknowledges (reconciles) a break-glass retro-review (AF-385). Resource: `break_glass_event`. Metadata: `query_request_id`, `datasource_id`, `submitted_by`. |
| `QUERY_FAILED` | Execution error. Metadata is enriched (AF-383) with `datasource_id` and `query_type` for UBA error-rate tracking. |
| `QUERY_CANCELLED` | Submitter cancels |
| `DATASOURCE_CREATED` | Admin creates datasource |
| `DATASOURCE_UPDATED` | Admin updates datasource config |
| `PERMISSION_GRANTED` | Admin grants user access to datasource |
| `PERMISSION_REVOKED` | Admin revokes access |
| `USER_LOGIN` | Successful login |
| `USER_LOGIN_FAILED` | Failed login attempt |
| `USER_CREATED` | New user created |
| `USER_DEACTIVATED` | User account deactivated |
| `API_KEY_CREATED` | A service-account API key was provisioned (e.g. by the `bootstrap` reconciler for CI/IaC — AF-452). Metadata: `email`, `api_key_name`, `role`. The raw key is never logged. |
| `API_KEY_UPDATED` | A declared service-account API key was rotated in place. |
| `USER_PASSWORD_RESET_REQUESTED` | User submitted the public forgot-password form for a real LOCAL account. Metadata: `email`, `source: "self_service"`. |
| `USER_PASSWORD_RESET_COMPLETED` | User successfully set a new password via the reset link. Metadata: `source: "self_service"`. All refresh tokens for the user are revoked. |
| `AI_CONFIG_CREATED` | Admin creates a new `ai_config` row via `POST /admin/ai-configs`. Metadata: `name`, `provider`, `model`. |
| `AI_CONFIG_UPDATED` | Admin updates an `ai_config` row via `PUT /admin/ai-configs/{id}`. Metadata includes only the fields that changed (`old_provider`, `new_provider`, `old_model`, `new_model`, `old_name`, `new_name`, `api_key_changed`, `prompt_changed`). |
| `AI_CONFIG_DELETED` | Admin deletes an `ai_config` row via `DELETE /admin/ai-configs/{id}`. |
| `KNOWLEDGE_DOCUMENT_CREATED` | Admin adds a RAG knowledge document via `POST /admin/ai-configs/{id}/knowledge-documents`. Metadata: `ai_config_id`, `title`, `chunk_count`. |
| `KNOWLEDGE_DOCUMENT_DELETED` | Admin deletes a RAG knowledge document. Metadata: `ai_config_id`. |
| `ORGANIZATION_CREATED` | Emitted when an organization is provisioned — by the env-driven bootstrap reconciler (metadata `source: "BOOTSTRAP"`, `change_kind: "CREATE"`, `name`, `slug`), or by a platform admin via `POST /api/v1/platform/organizations` (AF-456). Audited against the target org. |
| `ORGANIZATION_UPDATED` | Platform admin updates an org's name / quotas via `PUT /api/v1/platform/organizations/{id}` (AF-456). Audited against the target org. |
| `ORGANIZATION_DISABLED` | Platform admin disables a tenant via `POST /api/v1/platform/organizations/{id}/disable` (AF-456). Audited against the target org. |
| `ORGANIZATION_ENABLED` | Platform admin re-enables a tenant via `POST /api/v1/platform/organizations/{id}/enable` (AF-456). Audited against the target org. |
| `NOTIFICATION_CHANNEL_CREATED` / `NOTIFICATION_CHANNEL_UPDATED` | Emitted by the bootstrap reconciler when it creates or updates a `notification_channels` row from `accessflow.bootstrap.notificationChannels[*]`. Metadata: `source: "BOOTSTRAP"`, `change_kind`, `name`, `channel_type`, optional `changed_fields`. |
| `NOTIFICATION_DELIVERY_EXHAUSTED` | Emitted by the notifications dispatcher after a webhook channel exhausts its retry budget (1 initial attempt + 3 scheduled retries at +30 s / +2 min / +10 min). Resource: `notification_channel`, `actor_id = NULL`. Metadata: `source: "DISPATCHER"`, `channel_id`, `channel_type`, `event_type`, `attempt_count`, optional `last_http_status`, optional `last_error` (truncated to 500 chars). Other channels (Slack/Discord/Teams/Telegram/Email) are not yet audited on exhaustion. |
| `OAUTH2_CONFIG_UPDATED` | Emitted by the bootstrap reconciler when it applies a per-provider OAuth2 config from `accessflow.bootstrap.oauth2[*]`. Metadata: `source: "BOOTSTRAP"`, `change_kind: "UPDATE"`, `provider`, `config_type: "oauth2"`, optional `changed_fields`. |
| `SAML_CONFIG_UPDATED` | Emitted by the bootstrap reconciler when it applies the SAML configuration from `accessflow.bootstrap.saml`. Metadata: `source: "BOOTSTRAP"`, `change_kind: "UPDATE"`, `config_type: "saml"`, optional `changed_fields`. |
| `AUDIT_LOG_EXPORTED` | Admin called `GET /admin/audit-log/export.csv`. Resource: `audit_log`, no resource id. Metadata captures the export filter (`action`, `resource_type`, `actor_id`, `resource_id`, `from`, `to`) and the row counts (`matched_rows`, `truncated`). |
| `SLACK_APP_CONFIG_UPDATED` / `SLACK_APP_CONFIG_DELETED` | Admin creates/updates (`PUT`) or deletes (`DELETE`) the org's `slack_app_config` row. Resource: `slack_app_config`. Metadata on update: `app_id`, `active`. |
| `ACCESS_REQUEST_SUBMITTED` | User submits a JIT access-grant request. Resource: `access_grant_request`. Metadata: `datasource_id`, `requested_duration`, `can_read`/`can_write`/`can_ddl`. |
| `ACCESS_REQUEST_APPROVED` / `ACCESS_REQUEST_REJECTED` | Reviewer approves/rejects an access request. Metadata: `resulting_status`, optional `comment`. |
| `ACCESS_REQUEST_CANCELLED` | Requester cancels their own pending access request. |
| `ACCESS_GRANT_EXPIRED` | `AccessGrantExpiryJob` revokes a grant past its `expires_at` (system-driven, `actor_id = NULL`). Metadata: `reason: "expiry"`, optional `granted_permission_id`. |
| `ACCESS_GRANT_REVOKED` | Admin early-revokes an active grant via `POST /admin/access-requests/{id}/revoke`. Metadata: optional `comment`. |
| `ROUTING_POLICY_CREATED` / `ROUTING_POLICY_UPDATED` / `ROUTING_POLICY_DELETED` | Admin creates / updates / deletes a routing policy via the `/admin/routing-policies` CRUD endpoints. Resource: `routing_policy`. |
| `ROUTING_POLICY_REORDERED` | Admin reorders the org's routing policies via `PUT /admin/routing-policies/reorder`. Resource: `routing_policy`. |
| `MASKING_POLICY_CREATED` / `MASKING_POLICY_UPDATED` / `MASKING_POLICY_DELETED` | Admin creates / updates / deletes a masking policy via the `/datasources/{id}/masking-policies` CRUD endpoints. Resource: `masking_policy`. |
| `ROW_SECURITY_POLICY_CREATED` / `ROW_SECURITY_POLICY_UPDATED` / `ROW_SECURITY_POLICY_DELETED` | Admin creates / updates / deletes a row-security policy via the `/datasources/{id}/row-security-policies` CRUD endpoints (AF-380). Resource: `row_security_policy`. Applied row-security policy ids at execute time ride on `QUERY_EXECUTED` metadata (`applied_row_security_policy_ids`), not a separate action. |
| `DATA_CLASSIFICATION_TAG_ADDED` / `DATA_CLASSIFICATION_TAG_REMOVED` | Admin tags / untags a datasource table or column via the `/datasources/{id}/classification-tags` endpoints (AF-447). Resource: `data_classification_tag`. Metadata records the table, column, classification, and (on add) whether masking was auto-applied. |
| `COMPLIANCE_REPORT_EXPORTED` | AUDITOR/ADMIN exported a signed compliance report via `GET /admin/compliance/reports/export` (AF-459). Resource: `compliance_report`, no resource id. Metadata captures `report_type`, `format`, `period_from`, `period_to`, optional `datasource_id`, `row_count`, `truncated`, and the export's `content_sha256` + `signature` + `signature_algorithm` — chaining the export's hash into the tamper-evident log. |
| `QUERY_COMMENT_ADDED` / `QUERY_COMMENT_REPLIED` / `QUERY_COMMENT_RESOLVED` / `QUERY_COMMENT_REOPENED` | A collaborator opens / replies to / resolves / reopens an inline comment thread on a query in review (AF-441). Resource: `query_comment` (resource id = the comment id). Metadata: `query_id`, `comment_id`. |

Automated routing decisions reuse the existing `QUERY_APPROVED` / `QUERY_REJECTED` actions rather than introducing new ones: a policy `AUTO_APPROVE` / `AUTO_REJECT` writes the matching action with metadata `{ auto_approved: true | auto_rejected: true, source: "ROUTING_POLICY", routing_policy_id, reason }`, so external audit consumers distinguish a routing-driven decision from a human one by the `source` field.

Bootstrap reuses the existing `*_CREATED` / `*_UPDATED` actions for `DATASOURCE`, `AI_CONFIG`, `REVIEW_PLAN`, `USER`, and `SYSTEM_SMTP_UPDATED` — `metadata.source = "BOOTSTRAP"` plus `metadata.change_kind` is what distinguishes a bootstrap-driven write from an admin-UI-driven one. See [docs/05-backend.md → "Bootstrap audit semantics"](05-backend.md#bootstrap-audit-semantics).

### Audit Resource Types

`resource_type` is the snake_case form of one of the values in `AuditResourceType`: `query_request`, `datasource`, `user`, `api_key`, `permission`, `review_plan`, `notification_channel`, `ai_config`, `custom_jdbc_driver`, `system_smtp`, `user_invitation`, `organization`, `oauth2_config`, `saml_config`, `langfuse_config`, `audit_log`, `slack_app_config`, `access_grant_request`, `routing_policy`, `query_comment`, `break_glass_event`.

---

## bootstrap_state

Per-resource fingerprint cache used by the env-driven `bootstrap` reconciler to detect "no change" between the new spec and the previously persisted state, so a restart with unchanged env vars writes zero new rows to `audit_log`. Added in V41 ([AF-196](https://github.com/bablsoft/accessflow/issues/196)).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | UUID — references `organizations(id)` semantically; no SQL FK so the row survives org deletion (matches the audit-module convention from V14) |
| `resource_type` | VARCHAR(100) — one of the `BootstrapResourceType` enum names: `ORGANIZATION`, `ADMIN_USER`, `SERVICE_ACCOUNT`, `NOTIFICATION_CHANNEL`, `AI_CONFIG`, `REVIEW_PLAN`, `DATASOURCE`, `SAML_CONFIG`, `OAUTH2_CONFIG`, `SYSTEM_SMTP`. (`SERVICE_ACCOUNT` rows key on the service-account user UUID.) |
| `resource_id` | UUID — entity UUID for normal resources, the organization UUID for singleton-per-org configs (SAML, SystemSmtp), or a deterministic UUID derived via `UUID.nameUUIDFromBytes("OAUTH2:" + provider)` for OAuth2-per-provider rows |
| `spec_fingerprint` | VARCHAR(64) — lowercase hex SHA-256 of the canonical-sorted JSON of the spec |
| `updated_at` | TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP |

Unique constraint on `(organization_id, resource_type, resource_id)` (`uq_bootstrap_state_key`) so each resource is tracked once per org.

---

## notification_channels

Stores notification channel configurations (email, Slack, webhook, Discord, Telegram, Microsoft Teams, PagerDuty).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `channel_type` | ENUM: `EMAIL` \| `SLACK` \| `WEBHOOK` \| `DISCORD` \| `TELEGRAM` \| `MS_TEAMS` \| `PAGERDUTY` |
| `name` | VARCHAR(255) — human label |
| `config` | JSONB — channel-specific config (sensitive fields AES-encrypted) |
| `is_active` | BOOLEAN DEFAULT true |
| `created_at` | TIMESTAMPTZ |

Sensitive `config` fields encrypted with AES-256-GCM and masked on read:

- `EMAIL` → `smtp_password` → `smtp_password_encrypted`
- `WEBHOOK` → `secret` → `secret_encrypted`
- `TELEGRAM` → `bot_token` → `bot_token_encrypted`
- `PAGERDUTY` → `routing_key` → `routing_key_encrypted`

---

## slack_app_config

Per-organization Slack **app** configuration (AF-362). Distinct from a one-way `SLACK` row in `notification_channels`: when present and active, review-request messages are sent via the bot token (`chat.postMessage`) and carry interactive **Approve** / **Reject** buttons. One row per organization (UNIQUE on `organization_id`).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations`, UNIQUE — one Slack app per org |
| `app_id` | VARCHAR(64) NOT NULL, UNIQUE — Slack `api_app_id`; routes inbound callbacks back to this org |
| `bot_token_encrypted` | TEXT NOT NULL — AES-256-GCM via `CredentialEncryptionService`, `@JsonIgnore` |
| `signing_secret_encrypted` | TEXT NOT NULL — AES-256-GCM, `@JsonIgnore`; verifies the `X-Slack-Signature` HMAC |
| `default_channel_id` | VARCHAR(64) NOT NULL — channel for outbound messages when a `SLACK` channel has no override |
| `active` | BOOLEAN NOT NULL DEFAULT TRUE |
| `version` | BIGINT NOT NULL DEFAULT 0 — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ DEFAULT now() |

The admin API returns only `has_bot_token` / `has_signing_secret` booleans — never the secrets. On update, omitting a secret (or sending the `********` placeholder) keeps the existing ciphertext.

---

## user_slack_mapping

Maps an AccessFlow user to a Slack workspace user id (AF-362), populated by the `/accessflow link <code>` slash-command flow. Inbound Approve/Reject callbacks resolve the Slack user back to the AccessFlow user here, then run the decision through the same `ReviewService` guards as the REST API.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `user_id` | FK → `users`, UNIQUE — one Slack identity per user |
| `slack_user_id` | VARCHAR(64) NOT NULL — UNIQUE per `(organization_id, slack_user_id)` |
| `created_at` | TIMESTAMPTZ DEFAULT now() |

One-time link codes are not stored here — they live in Redis (`slack:link:<code>`, single-use, TTL `accessflow.notifications.slack.link-code-ttl`).

---

## system_smtp_config

Per-organization global SMTP configuration. Drives user-invitation emails and acts as the fallback EMAIL channel when an organization has no active EMAIL row in `notification_channels`. One row per organization (enforced by UNIQUE on `organization_id`).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` ON DELETE CASCADE, UNIQUE |
| `host` | VARCHAR(255) NOT NULL |
| `port` | INTEGER NOT NULL |
| `username` | VARCHAR(255), nullable — for anonymous-bind SMTP servers |
| `password_encrypted` | TEXT, nullable — AES-256-GCM via `CredentialEncryptionService` |
| `tls` | BOOLEAN NOT NULL DEFAULT TRUE — STARTTLS toggle |
| `from_address` | VARCHAR(255) NOT NULL |
| `from_name` | VARCHAR(255), nullable — display name attached to the From header |
| `created_at` | TIMESTAMPTZ DEFAULT now() |
| `updated_at` | TIMESTAMPTZ DEFAULT now() |

`password_encrypted` is `@JsonIgnore`-equivalent on the response side: the admin API returns `"********"` as the `smtp_password` field when a password is set, and accepts the same masked placeholder on update (PUT) to mean "keep existing".

---

## user_invitations

Single-use email invitations. The token is delivered via the organization's system SMTP and exchanged for a new local-account user when the recipient sets a password.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` ON DELETE CASCADE |
| `email` | VARCHAR(255) NOT NULL |
| `role` | `user_role_type` enum — role the invited user receives on accept |
| `display_name` | VARCHAR(255), nullable |
| `token_hash` | VARCHAR(64) NOT NULL UNIQUE — SHA-256 hex of the plaintext token; the plaintext token is sent in the email only and never persisted |
| `status` | `user_invitation_status` enum: `PENDING` \| `ACCEPTED` \| `REVOKED` \| `EXPIRED` |
| `expires_at` | TIMESTAMPTZ NOT NULL — controlled by `accessflow.security.invitation.ttl` (default `P7D`) |
| `accepted_at` | TIMESTAMPTZ, nullable — set on successful accept |
| `revoked_at` | TIMESTAMPTZ, nullable — set when the admin revokes |
| `invited_by_user_id` | FK → `users` |
| `created_at` | TIMESTAMPTZ DEFAULT now() |

Indexes:

```sql
CREATE INDEX idx_user_invitations_org_status_created
    ON user_invitations(organization_id, status, created_at DESC);

CREATE UNIQUE INDEX uq_user_invitations_pending_email
    ON user_invitations(organization_id, LOWER(email))
    WHERE status = 'PENDING';
```

The partial UNIQUE index prevents two simultaneous pending invitations for the same email within an organization. Resending an invitation rotates the token (so old emailed links stop working) and refreshes `expires_at` without creating a duplicate row.

---

## password_reset_tokens

Single-use, short-lived tokens that let a user reset a forgotten password. Issued by the public `POST /api/v1/auth/password/forgot` endpoint; consumed by `POST /api/v1/auth/password/reset/{token}`. The flow is enumeration-safe — the request endpoint always returns 202 regardless of whether the email matches an active LOCAL account.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `user_id` | FK → `users` ON DELETE CASCADE |
| `organization_id` | FK → `organizations` ON DELETE CASCADE |
| `token_hash` | VARCHAR(64) NOT NULL UNIQUE — SHA-256 hex of the plaintext token; plaintext is sent in the email only and never persisted |
| `status` | `password_reset_status` enum: `PENDING` \| `USED` \| `REVOKED` \| `EXPIRED` |
| `expires_at` | TIMESTAMPTZ NOT NULL — controlled by `accessflow.security.password-reset.ttl` (default `PT1H`) |
| `used_at` | TIMESTAMPTZ, nullable — set when the token is consumed |
| `revoked_at` | TIMESTAMPTZ, nullable — set when a subsequent reset request supersedes a prior pending row |
| `created_at` | TIMESTAMPTZ DEFAULT now() |

Indexes:

```sql
CREATE INDEX idx_password_reset_tokens_user_status_created
    ON password_reset_tokens(user_id, status, created_at DESC);

CREATE UNIQUE INDEX uq_password_reset_tokens_pending_user
    ON password_reset_tokens(user_id) WHERE status = 'PENDING';
```

The partial UNIQUE index allows only one pending token per user; when a user requests a second reset the service marks the existing `PENDING` row as `REVOKED` before inserting the new one. Successful reset additionally revokes all active refresh tokens via `core.api.SessionRevocationService`, so any logged-in sessions on the account are kicked out.

The audit table records `USER_PASSWORD_RESET_REQUESTED` when the requester resolves to a real LOCAL account, and `USER_PASSWORD_RESET_COMPLETED` on a successful reset.

---

## user_notifications

In-app notification inbox rows, persisted per recipient. Each domain event that the
notifications module dispatches also writes one row per recipient here so the bell-icon
inbox can show history, unread counts, and act on individual entries. The
`event_type` mirrors `notification_event_type` (`QUERY_SUBMITTED`, `QUERY_APPROVED`,
`QUERY_REJECTED`, `REVIEW_TIMEOUT`, `AI_HIGH_RISK`); `TEST` events are skipped.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `user_id` | FK → `users` ON DELETE CASCADE — recipient |
| `organization_id` | FK → `organizations` ON DELETE CASCADE |
| `event_type` | VARCHAR(64) — one of the `NotificationEventType` values |
| `query_request_id` | FK → `query_requests` ON DELETE CASCADE, nullable |
| `payload` | JSONB — denormalised render context (datasource name, submitter, risk_level, reviewer comment, etc.) |
| `is_read` | BOOLEAN DEFAULT false |
| `created_at` | TIMESTAMPTZ DEFAULT now() |
| `read_at` | TIMESTAMPTZ, nullable |

Indexes:

```sql
CREATE INDEX idx_user_notifications_user_created
    ON user_notifications(user_id, created_at DESC);
CREATE INDEX idx_user_notifications_user_unread
    ON user_notifications(user_id) WHERE is_read = FALSE;
```

---

## localization_config

Org-singleton localization settings: which UI languages users in the organization may pick from, the default language for new accounts, and the language the AI analyzer responds in for every query in the org. One row per organization.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` UNIQUE |
| `available_languages` | TEXT[] NOT NULL — non-empty array of BCP-47 codes (subset of `en`, `es`, `de`, `fr`, `zh-CN`, `ru`, `hy`) |
| `default_language` | VARCHAR(20) NOT NULL — must be a member of `available_languages` (CHECK constraint) |
| `ai_review_language` | VARCHAR(20) NOT NULL — any supported BCP-47 code; independent of the user-facing allow-list |
| `version` | BIGINT — optimistic locking |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

When no row exists for an organization, `LocalizationConfigService.getOrDefault` returns a transient view with `available_languages = [en]`, `default_language = en`, `ai_review_language = en` so the system has sane defaults out of the box.

---

## saml_configurations

Stores SAML 2.0 Identity Provider configuration for an organization. Optional — rows only exist for orgs that have configured SSO.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` UNIQUE |
| `idp_entity_id` | VARCHAR(500) |
| `idp_sso_url` | VARCHAR(500) |
| `idp_certificate` | TEXT — X.509 certificate PEM |
| `sp_entity_id` | VARCHAR(500) — Service Provider entity ID |
| `attribute_mapping` | JSONB — maps SAML assertion attributes to user fields |
| `attr_groups` | VARCHAR(255) NULL — IdP attribute name carrying the user's group claim values (multi-valued). When unset, no group sync happens. |
| `group_mappings` | JSONB NOT NULL DEFAULT '{}' — maps IdP claim value to AccessFlow group UUID (`{"idp-group": "<uuid>"}`). Drives the per-login membership sync (only `source = 'IDP'` rows are touched). |
| `auto_provision_users` | BOOLEAN DEFAULT true — create users on first SSO login |
| `default_role` | ENUM: `ANALYST` \| `READONLY` — role assigned to auto-provisioned users |
| `created_at` | TIMESTAMPTZ |

---

## oauth2_config

Stores OAuth 2.0 / OIDC provider configuration for an organization. One row per `(organization_id, provider)` pair; rows only exist for providers the admin has configured. The dynamic `ClientRegistrationRepository` builds Spring Security `ClientRegistration`s on demand from these rows and evicts the cache on update / delete (no application restart).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `provider` | ENUM `oauth2_provider_type`: `GOOGLE` \| `GITHUB` \| `MICROSOFT` \| `GITLAB` \| `OIDC` \| `GITHUB_ENTERPRISE` \| `GITLAB_ENTERPRISE` |
| `client_id` | VARCHAR(512) NOT NULL |
| `client_secret_encrypted` | TEXT NOT NULL — AES-256-GCM ciphertext via `CredentialEncryptionService`. `@JsonIgnore`d on the entity; admin API exposes only `client_secret_configured: boolean`. |
| `scopes_override` | VARCHAR(1024) — space-separated. NULL means use the provider template default. |
| `tenant_id` | VARCHAR(255) — required for `MICROSOFT` (e.g. `common`, `organizations`, or a tenant GUID); NULL for the others. |
| `display_name` | VARCHAR(255) — required for `OIDC` (rendered as "Continue with {display_name}" on the login page); optional override for `GITHUB_ENTERPRISE` / `GITLAB_ENTERPRISE` (falls back to the built-in label); NULL/ignored for the four built-in cloud providers (their display name is hard-coded). |
| `authorization_uri` | VARCHAR(2048) — required for `OIDC` (IdP's authorization endpoint); NULL/ignored otherwise. |
| `token_uri` | VARCHAR(2048) — required for `OIDC` (IdP's token endpoint); NULL/ignored otherwise. |
| `user_info_uri` | VARCHAR(2048) — required for `OIDC` (IdP's UserInfo endpoint); NULL/ignored otherwise. |
| `jwk_set_uri` | VARCHAR(2048) — required for `OIDC` (IdP's JWK set URL); NULL/ignored otherwise. |
| `issuer_uri` | VARCHAR(2048) — required for `OIDC` (matches the `iss` claim in the ID token); NULL/ignored otherwise. |
| `user_name_attribute` | VARCHAR(255) — claim name read as the OAuth2 user-name. NULL falls back to the OIDC default `sub`. Ignored for the four built-in providers (their claim names live in `OAuth2ProviderTemplate`). |
| `email_attribute` | VARCHAR(255) — claim name read as the user's email. NULL falls back to `email`. Ignored for the four built-in providers. |
| `email_verified_attribute` | VARCHAR(255) — claim name read as the email-verified flag. NULL falls back to `email_verified`. Ignored for the four built-in providers. |
| `display_name_attribute` | VARCHAR(255) — claim name read as the user's display name. NULL falls back to `name`. Ignored for the four built-in providers. |
| `groups_attribute` | VARCHAR(255) — claim name read for group/organization membership (used by `allowed_organizations` enforcement). NULL = no groups extracted (the OIDC allowlist is then effectively empty; restrict via `allowed_email_domains` instead). Ignored for the four built-in providers (they each have hard-coded membership logic). |
| `base_url` | VARCHAR(2048) — required for `GITHUB_ENTERPRISE` and `GITLAB_ENTERPRISE` (origin of the self-hosted instance, e.g. `https://github.acme.corp`). Must be `https://` with no path, query, or fragment. AccessFlow appends the well-known sub-paths (`/login/oauth/authorize`, `/api/v3/*` for GitHub Enterprise; `/oauth/authorize`, `/oauth/userinfo`, `/oauth/discovery/keys` for GitLab) compiled into `OAuth2ProviderTemplate` — only the origin is operator-editable. NULL/ignored for all other providers. |
| `allowed_organizations` | TEXT[] — optional allowlist of provider-native organization identifiers. Login is rejected with `OAUTH2_ORG_NOT_ALLOWED` unless the user's membership intersects this list. NULL/empty = no restriction. Provider semantics: GitHub / GitHub Enterprise org logins (case-sensitive, requires the `read:org` scope), GitLab / GitLab self-managed full group paths from the OIDC `groups` claim, Microsoft AAD group object IDs from the `groups` claim, OIDC group identifiers from the claim named in `groups_attribute`. Ignored for `GOOGLE` (use `allowed_email_domains`). |
| `allowed_email_domains` | TEXT[] — optional allowlist of email domains; login is rejected with `OAUTH2_EMAIL_DOMAIN_NOT_ALLOWED` unless the user's email domain (case-insensitive) matches one entry. NULL/empty = no restriction. Doubles as the Google Workspace-domain check. |
| `group_mappings` | JSONB NOT NULL DEFAULT '{}' — maps IdP group/organization claim value to AccessFlow group UUID (`{"idp-group": "<uuid>"}`). The claim name is `groups_attribute` (OIDC) or the provider-native organization claim (built-in providers). Drives the per-login membership sync (only `source = 'IDP'` rows on `user_group_memberships` are touched). |
| `default_role` | ENUM `user_role_type` — role assigned to users JIT-provisioned by this provider. Defaults to `ANALYST`. |
| `active` | BOOLEAN NOT NULL DEFAULT FALSE — only active providers appear on the login page. Activating a `GITHUB` or `GITHUB_ENTERPRISE` row with a non-empty `allowed_organizations` is rejected unless `scopes_override` contains `read:org`. Activating an `OIDC` row requires `display_name`, `authorization_uri`, `token_uri`, `user_info_uri`, `jwk_set_uri`, and `issuer_uri` to be set. Activating a `GITHUB_ENTERPRISE` or `GITLAB_ENTERPRISE` row requires `base_url` to be a valid `https://` origin. |
| `version` | BIGINT — `@Version` optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Unique constraint: `(organization_id, provider)`. Partial index on `(organization_id)` where `active` for the public providers endpoint.

---

## langfuse_config

Per-organization [Langfuse](https://langfuse.com) integration settings — one row per organization (singleton, like `saml_config`). Drives both LLM-call **tracing** (the analyzer posts a trace per AI analysis to the Langfuse ingestion API) and **prompt management** (analyzer prompts fetched at render time per `ai_config.langfuse_prompt_name`). The decrypted credentials are cached per org and evicted on update. See [docs/05-backend.md → "Langfuse integration"](05-backend.md#langfuse-integration).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations`, UNIQUE (one row per org) |
| `enabled` | BOOLEAN NOT NULL DEFAULT FALSE — master switch; when off, neither tracing nor prompt fetch runs |
| `host` | VARCHAR(500) nullable — Langfuse base URL; blank falls back to `accessflow.langfuse.default-host` (`https://cloud.langfuse.com`) |
| `public_key` | VARCHAR(255) nullable — project public key (`pk-lf-…`) |
| `secret_key_encrypted` | TEXT nullable — AES-256-GCM ciphertext via `CredentialEncryptionService`; `@JsonIgnore`d. The admin API exposes only `secret_key_configured: boolean` (masked `********`). |
| `tracing_enabled` | BOOLEAN NOT NULL DEFAULT TRUE — emit a trace per AI analysis |
| `prompt_management_enabled` | BOOLEAN NOT NULL DEFAULT FALSE — fetch analyzer prompts from Langfuse by name on AI configs |
| `version` | BIGINT — `@Version` optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Tracing and prompt fetch are **best-effort and non-blocking** — a Langfuse outage or misconfiguration never affects the analysis result (failures are logged and swallowed; prompt fetch falls back to the locally stored template).

---

## attestation_campaign

A recurring access-recertification campaign (AF-384, Flyway V99). Owned by the `attestation` module.
A campaign snapshots the organization's (or a single datasource's) standing
`datasource_user_permissions` grants into `attestation_item` rows at open time; reviewers certify or
revoke each; at the due date `AttestationCampaignCloseJob` applies `pending_default` to anything still
`PENDING`. `organization_id` / `datasource_id` / `created_by` are bare UUIDs (no FK), like
`break_glass_events` / `query_snapshots`, so a campaign survives the deletion of its source rows.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | UUID NOT NULL (bare) |
| `name` | TEXT NOT NULL |
| `description` | TEXT nullable |
| `scope` | ENUM `attestation_campaign_scope`: `ORGANIZATION` \| `DATASOURCE` |
| `datasource_id` | UUID nullable (bare) — NOT NULL iff `scope=DATASOURCE` (app-enforced) |
| `status` | ENUM `attestation_campaign_status`: `SCHEDULED` \| `OPEN` \| `CLOSED` \| `CANCELLED`; DEFAULT `SCHEDULED` |
| `pending_default` | ENUM `attestation_pending_default`: `KEEP` \| `REVOKE`; DEFAULT `KEEP` |
| `scheduled_open_at` | TIMESTAMPTZ NOT NULL |
| `due_at` | TIMESTAMPTZ NOT NULL |
| `opened_at` / `closed_at` | TIMESTAMPTZ nullable |
| `total_items` | INT NOT NULL DEFAULT 0 |
| `created_by` | UUID NOT NULL (bare) |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ DEFAULT now() |

Indexes: `(organization_id, status, created_at DESC)`; partial `(scheduled_open_at) WHERE
status='SCHEDULED'` (open-job scan); partial `(due_at) WHERE status='OPEN'` (close-job scan).
State machine: `SCHEDULED → OPEN → CLOSED`; `SCHEDULED → CANCELLED`.

## attestation_item

One access grant under review — a frozen snapshot of a `datasource_user_permissions` row at campaign
open (AF-384, Flyway V99). The denormalized permission columns + `permission_snapshot` JSONB preserve
the exact grant shape for evidence even after the permission is revoked or deleted. `permission_id` is
a **bare** reference (no FK) used only as the revoke target.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `campaign_id` | UUID NOT NULL FK → `attestation_campaign(id)` ON DELETE CASCADE (the only real FK) |
| `organization_id` | UUID NOT NULL (bare) |
| `permission_id` | UUID NOT NULL (bare) — the snapshotted grant; tolerated as already-gone on revoke |
| `datasource_id` / `datasource_name` | UUID / TEXT NOT NULL — denormalized |
| `subject_user_id` / `subject_user_email` | UUID / TEXT NOT NULL — the grant holder |
| `subject_user_display_name` | TEXT nullable |
| `can_read` / `can_write` / `can_ddl` / `can_break_glass` | BOOLEAN NOT NULL DEFAULT false |
| `permission_expires_at` / `permission_created_at` | TIMESTAMPTZ nullable |
| `permission_snapshot` | JSONB NOT NULL — full serialized `DatasourcePermissionView` |
| `decision` | ENUM `attestation_item_decision`: `PENDING` \| `CERTIFIED` \| `REVOKED`; DEFAULT `PENDING` |
| `close_reason` | ENUM `attestation_item_close_reason`: `REVIEWER` \| `AUTO_DEFAULT_KEEP` \| `AUTO_DEFAULT_REVOKE`; nullable |
| `decided_by` | UUID nullable (bare) — null for the end-of-campaign automatic default |
| `decided_at` | TIMESTAMPTZ nullable |
| `decision_comment` | TEXT nullable |
| `version` | BIGINT — optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ DEFAULT now() |

Constraints: `UNIQUE(campaign_id, permission_id)` (open-idempotency backstop); indexes
`(campaign_id, decision)` (worklist + close sweep) and `(organization_id, subject_user_id)`. Item
state machine: `PENDING → CERTIFIED` \| `PENDING → REVOKED` (terminal; idempotent replay).

---

## api_connectors (AF-500)

Governed outbound API targets, per organization. Enums (`snake_case`, no `_enum` suffix):
`api_protocol` (`REST`/`SOAP`/`GRAPHQL`/`GRPC`), `api_auth_method`
(`NONE`/`API_KEY`/`BEARER_TOKEN`/`BASIC`/`OAUTH2_CLIENT_CREDENTIALS`/`CUSTOM_HEADER`/`MTLS`),
plus the outbound-OAuth2 enums (#506) `oauth2_grant_type`
(`CLIENT_CREDENTIALS`/`REFRESH_TOKEN`/`PASSWORD`) and `oauth2_client_auth`
(`CLIENT_SECRET_BASIC`/`CLIENT_SECRET_POST`).

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `organization_id` | UUID | Bare UUID (no FK), org-scoped. |
| `name` | VARCHAR(255) | Unique per org (`uq_api_connectors_org_name`). |
| `protocol` | `api_protocol` | |
| `base_url` | TEXT | |
| `default_headers` | JSONB | Object merged into every outbound call. Default `{}`. |
| `trace_header_mapping` | JSONB | Admin-renamable header keys that carry the W3C trace context (#517, AF-517). Default `{"traceparent":"traceparent","tracestate":"tracestate"}`. |
| `timeout_ms` | INTEGER | Per-call timeout. Default 30000. |
| `tls_verify` | BOOLEAN | Default true. |
| `auth_method` | `api_auth_method` | Default `NONE`. |
| `auth_credentials_encrypted` | TEXT | AES-256-GCM ciphertext of the auth secret map (API_KEY/BEARER/BASIC/CUSTOM_HEADER). `@JsonIgnore`; never serialized. |
| `oauth2_token_uri` / `oauth2_client_id` / `oauth2_scopes` / `oauth2_audience` / `oauth2_username` | TEXT | Outbound OAuth2 non-secret config (#506), nullable, returnable in GET. |
| `oauth2_client_secret_encrypted` / `oauth2_refresh_token_encrypted` / `oauth2_password_encrypted` | TEXT | AES-256-GCM ciphertext of the outbound OAuth2 secrets. `@JsonIgnore`; never serialized (read views expose `oauth2_*_configured` booleans). |
| `oauth2_grant_type` | `oauth2_grant_type` | Default `CLIENT_CREDENTIALS`. |
| `oauth2_client_auth` | `oauth2_client_auth` | Default `CLIENT_SECRET_BASIC`. |
| `review_plan_id` / `ai_config_id` | UUID | Bare UUIDs into core. |
| `ai_analysis_enabled` / `text_to_api_enabled` | BOOLEAN | |
| `require_review_reads` / `require_review_writes` | BOOLEAN | Map safe vs mutating methods to review. |
| `max_response_bytes` | BIGINT | Response-size cap. Default 1 MiB. |
| `is_active` | BOOLEAN | Default true. |
| `created_at` | TIMESTAMPTZ | |

## api_schemas (AF-500)

Uploaded schema documents per connector; the normalized operation catalog is cached on the row.
Enum `api_schema_type` (`OPENAPI`/`WSDL`/`GRAPHQL_SDL`/`GRPC_PROTO`).

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `connector_id` | UUID | FK → `api_connectors` `ON DELETE CASCADE`. |
| `schema_type` | `api_schema_type` | |
| `raw_content` / `source_url` | TEXT | One of: uploaded body or fetched URL. |
| `parsed_operations` | JSONB | Cached `ApiOperation[]` (operationId, verb, path, summary, write). Default `[]`. |
| `operation_count` | INTEGER | |
| `created_at` | TIMESTAMPTZ | |

## api_connector_user_permissions (AF-500)

Per-user, per-connector grants — how an admin shares governed connectivity with the team. Mirrors
`datasource_user_permissions`.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `connector_id` | UUID | FK → `api_connectors` `ON DELETE CASCADE`. Unique with `user_id`. |
| `user_id` | UUID | Bare UUID. |
| `can_read` / `can_write` / `can_break_glass` | BOOLEAN | |
| `expires_at` | TIMESTAMPTZ | JIT expiry (nullable). |
| `allowed_operations` | TEXT[] | Operation-id subset the user may call (null = all). |
| `restricted_response_fields` | TEXT[] | Dot-paths masked in responses for this user. |
| `created_by` | UUID | |
| `created_at` | TIMESTAMPTZ | |

## api_requests (AF-500)

Governed API calls (migration V101), mirroring `query_requests`. Reuses the shared `query_status` /
`submission_reason` enum types.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `connector_id` / `organization_id` / `submitted_by` | UUID | Bare UUIDs. |
| `operation_id` | TEXT | Null for a free-form call. |
| `verb` | VARCHAR(16) | HTTP method / GraphQL op / gRPC method. |
| `request_path` | TEXT | |
| `request_headers` | JSONB | Sanitized header set. |
| `request_body` | TEXT | Raw text, or the base64 of a binary/file body (#517). |
| `body_type` | `api_body_type` | How the body is composed (#517, AF-517): `NONE` / `RAW` / `FORM_DATA` / `FORM_URLENCODED` / `BINARY`. Default `RAW`. |
| `request_content_type` | TEXT | Content-Type for a `RAW` body (#517). |
| `query_params` | JSONB | Key/value query parameters appended to the URL (#517). Default `{}`. |
| `form_fields` | JSONB | `[{key,type(TEXT\|FILE),value,filename,contentType}]` for `FORM_DATA` / `FORM_URLENCODED` bodies; file parts carry base64 (#517). Default `[]`. |
| `binary_filename` | TEXT | Filename of a `BINARY` body (#517). |
| `is_write` | BOOLEAN | Read/write classification (drives review routing). |
| `status` | `query_status` | Lifecycle. |
| `submission_reason` | `submission_reason` | `EMERGENCY_ACCESS` = break-glass. |
| `justification` / `ai_analysis_id` / `scheduled_for` / `required_approvals` | — | |
| `trace_id` / `span_id` | TEXT | W3C trace context ids generated at submit, propagated as `traceparent` on execution, and filterable (#517, AF-517). Indexed `(organization_id, trace_id)` and `(organization_id, span_id)`. |
| `response_status_code` / `response_duration_ms` / `response_bytes` / `response_truncated` | — | Execution metadata. |
| `response_snapshot` | TEXT | Size-capped, field-masked response body (immutable). |
| `response_content_type` | TEXT | Upstream `Content-Type` of the captured response — lets the stored snapshot be downloaded in its correct format (#517). |
| `error_message` / `submitted_ip` / `submitted_user_agent` | — | |
| `version` | BIGINT | `@Version` optimistic lock. |
| `created_at` / `updated_at` | TIMESTAMPTZ | |

## api_review_decisions (AF-500)

Per-stage reviewer decisions on an API request (mirror of `review_decisions`). `decision` reuses the
shared `decision` enum; unique `(api_request_id, reviewer_id, stage)` backstops idempotency. FK
`api_request_id` → `api_requests` `ON DELETE CASCADE`.

## api_routing_policies (AF-500)

Attribute-based routing for API calls (enum `api_routing_action`:
`AUTO_APPROVE`/`AUTO_REJECT`/`REQUIRE_APPROVALS`/`ESCALATE`). `conditions` JSONB constrains
`write`/`verbs`/`operations`/`minRiskLevel`; lowest `priority` wins; `connector_id` null = all.

**Extensions (V101).** `ai_analyses.query_request_id` becomes nullable and `api_request_id` is added
(CHECK exactly-one), keeping AI token-budget accounting unified. `break_glass_events` gains
`api_request_id` + `connector_id`, and `query_request_id` / `datasource_id` become nullable, so a
break-glass retro-review can target an API request.

---

## Data Lifecycle Manager (AF-499)

The `lifecycle` module (migration **V103**) adds retention + right-to-erasure governance. New enums
(`snake_case`, no `_enum` suffix): `lifecycle_action` (`HARD_DELETE`/`SOFT_DELETE`/`PSEUDONYMIZE`),
`lifecycle_transform` (`SHA256_SALTED`/`FORMAT_PRESERVING`/`TOKENIZATION`), `lifecycle_subject_type`
(`USER_ID`/`EMAIL`/`CUSTOM`), `erasure_status`
(`PENDING_SCOPE_AI`/`PENDING_REVIEW`/`APPROVED`/`EXECUTED`/`REJECTED`/`FAILED`/`CANCELLED`),
`erasure_decision` (`APPROVED`/`REJECTED`), `lifecycle_run_kind`
(`RETENTION_POLICY`/`ERASURE_REQUEST`), `lifecycle_run_status`
(`STAGED`/`EXECUTING`/`COMPLETED`/`FAILED`).

### retention_policies

Per-datasource, admin-defined retention rule. Targets a `target_table` and/or `classification_tag`
(at least one required, app-enforced) plus an optional `target_columns text[]`; a `retention_window`
(ISO-8601 period/duration) measured against `timestamp_column`; an `action` (with `transform_type`
required only for `PSEUDONYMIZE`, and an optional `soft_delete_column`); `enabled`. UUID PK,
`@Version`, `organization_id`/`datasource_id`/`created_by` are bare UUIDs (no FK). Indexed by org,
by enabled+org+datasource (scan job), and by datasource (proxy directive resolution).

### deletion_requests

A right-to-erasure request keyed on a `subject_identifier` + `subject_type`, flowing the
`erasure_status` state machine. Holds the nullable `ai_scope_analysis_id`, an immutable
`scope_snapshot` JSONB, `estimated_rows`/`affected_rows`, `executed_at`, `failure_reason`. Indexed by
org+status and a partial `PENDING_REVIEW` index for the review queue.

### deletion_request_decisions

Approval-chain rows (mirrors `access_grant_decision`): `request_id` FK (cascade), `reviewer_id`,
`stage`, `decision` (`erasure_decision`), `comment`, with `UNIQUE(request_id, reviewer_id, stage)`.

### lifecycle_runs

Execution ledger backing the activity view + compliance report: `kind`, one of
`policy_id`/`deletion_request_id`, `status` (`lifecycle_run_status`), `action`, `matched_tables`
JSONB, `affected_rows`, `method`, `started_at`/`finished_at`. Indexed by org+created and a partial
`STAGED` index for the scan job.

### lifecycle_salt

Per-org pseudonymization salt: `organization_id` PK, `salt_encrypted` (AES-256-GCM via
`CredentialEncryptionService`, never serialized), `version` + `rotated_at` for rotation.

---

## Database Indexes (Key)

```sql
-- Query requests: common filter patterns
CREATE INDEX idx_query_requests_status ON query_requests(status);
CREATE INDEX idx_query_requests_datasource ON query_requests(datasource_id);
CREATE INDEX idx_query_requests_submitter ON query_requests(submitted_by);
CREATE INDEX idx_query_requests_created ON query_requests(created_at DESC);
-- Datasource health dashboard: per-datasource time-window aggregate (V52, AF-365)
CREATE INDEX idx_query_requests_datasource_created_at ON query_requests(datasource_id, created_at);

-- Audit log: time-range queries
CREATE INDEX idx_audit_log_created ON audit_log(organization_id, created_at DESC);
CREATE INDEX idx_audit_log_actor ON audit_log(actor_id, created_at DESC);
CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);

-- Permissions: lookup by user+datasource
CREATE UNIQUE INDEX idx_permissions_user_ds ON datasource_user_permissions(user_id, datasource_id);
```
