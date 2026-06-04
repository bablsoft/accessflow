# 03 — Data Model

All entities are stored in AccessFlow's **internal PostgreSQL database**. Customer databases are never written to except through the proxied, approved query path.

> **Naming convention:** All tables use `snake_case`. All primary keys are `UUID`. All timestamps are `TIMESTAMPTZ`.

> **Startup bootstrap.** The `bootstrap` module (see [docs/05-backend.md → "Startup bootstrap"](05-backend.md#startup-bootstrap-env-driven-admin-config) and [docs/09-deployment.md → "Bootstrap configuration"](09-deployment.md#bootstrap-configuration)) seeds the rows below from `ACCESSFLOW_BOOTSTRAP_*` env vars on every backend start. No new tables / columns / enums are introduced for that feature — bootstrap reuses the existing unique constraints (`(organization_id, name)`, `(organization_id, provider)`, or singleton-per-org) as the upsert keys.

---

## organizations

Represents a tenant. A deployment hosts a single organization.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `name` | VARCHAR(255) NOT NULL |
| `slug` | VARCHAR(100) UNIQUE — URL-safe identifier |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

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
| `role` | ENUM: `ADMIN` \| `REVIEWER` \| `ANALYST` \| `READONLY` |
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
| `db_type` | ENUM: `POSTGRESQL` \| `MYSQL` \| `MARIADB` \| `ORACLE` \| `MSSQL` \| `CUSTOM` — `CUSTOM` is paired with `custom_driver_id` + `jdbc_url_override` for free-form dynamic datasources |
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
| `ai_config_id` | FK → `ai_config(id)` NULL, ON DELETE SET NULL — which AI configuration runs analysis for this datasource. Required (and enforced by the service layer) when `ai_analysis_enabled = true`. |
| `custom_driver_id` | FK → `custom_jdbc_driver(id)` NULL, ON DELETE RESTRICT — when set, the proxy uses the uploaded driver's per-driver classloader instead of the bundled registry entry. Required when `db_type=CUSTOM`. |
| `jdbc_url_override` | TEXT NULL — free-form JDBC connection string; required when `db_type=CUSTOM` (and rejected for any bundled `db_type`). |
| `read_replica_jdbc_url` | TEXT NULL — when set, SELECT queries are routed to a sibling HikariCP pool built from this URL. INSERT/UPDATE/DELETE/DDL always hit the primary. Reuses the primary's driver class. |
| `read_replica_username` | VARCHAR(255) NULL — username for the replica pool. When `NULL` the primary `username` is reused. |
| `read_replica_password_encrypted` | TEXT NULL — AES-256-GCM encrypted; same key (`ENCRYPTION_KEY`) as `password_encrypted`. When `NULL`, the primary `password_encrypted` is reused. `@JsonIgnore` on the entity. |
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

On the AI-skipped path (`datasource.ai_analysis_enabled=false`) the risk-based operands (`risk_level`, `risk_score`) evaluate to **false** — there is no AI signal. Routing is **not** run on the AI-failure path.

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
| `missing_indexes_detected` | BOOLEAN |
| `affects_row_estimate` | BIGINT nullable — estimated rows impacted |
| `prompt_tokens` | INTEGER |
| `completion_tokens` | INTEGER |
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
| `QUERY_EXECUTED` | Proxy executes approved query |
| `QUERY_FAILED` | Execution error |
| `QUERY_CANCELLED` | Submitter cancels |
| `DATASOURCE_CREATED` | Admin creates datasource |
| `DATASOURCE_UPDATED` | Admin updates datasource config |
| `PERMISSION_GRANTED` | Admin grants user access to datasource |
| `PERMISSION_REVOKED` | Admin revokes access |
| `USER_LOGIN` | Successful login |
| `USER_LOGIN_FAILED` | Failed login attempt |
| `USER_CREATED` | New user created |
| `USER_DEACTIVATED` | User account deactivated |
| `USER_PASSWORD_RESET_REQUESTED` | User submitted the public forgot-password form for a real LOCAL account. Metadata: `email`, `source: "self_service"`. |
| `USER_PASSWORD_RESET_COMPLETED` | User successfully set a new password via the reset link. Metadata: `source: "self_service"`. All refresh tokens for the user are revoked. |
| `AI_CONFIG_CREATED` | Admin creates a new `ai_config` row via `POST /admin/ai-configs`. Metadata: `name`, `provider`, `model`. |
| `AI_CONFIG_UPDATED` | Admin updates an `ai_config` row via `PUT /admin/ai-configs/{id}`. Metadata includes only the fields that changed (`old_provider`, `new_provider`, `old_model`, `new_model`, `old_name`, `new_name`, `api_key_changed`, `prompt_changed`). |
| `AI_CONFIG_DELETED` | Admin deletes an `ai_config` row via `DELETE /admin/ai-configs/{id}`. |
| `ORGANIZATION_CREATED` | Emitted by the env-driven bootstrap reconciler when it provisions a brand-new organization. Metadata: `source: "BOOTSTRAP"`, `change_kind: "CREATE"`, `name`, `slug`. |
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

Automated routing decisions reuse the existing `QUERY_APPROVED` / `QUERY_REJECTED` actions rather than introducing new ones: a policy `AUTO_APPROVE` / `AUTO_REJECT` writes the matching action with metadata `{ auto_approved: true | auto_rejected: true, source: "ROUTING_POLICY", routing_policy_id, reason }`, so external audit consumers distinguish a routing-driven decision from a human one by the `source` field.

Bootstrap reuses the existing `*_CREATED` / `*_UPDATED` actions for `DATASOURCE`, `AI_CONFIG`, `REVIEW_PLAN`, `USER`, and `SYSTEM_SMTP_UPDATED` — `metadata.source = "BOOTSTRAP"` plus `metadata.change_kind` is what distinguishes a bootstrap-driven write from an admin-UI-driven one. See [docs/05-backend.md → "Bootstrap audit semantics"](05-backend.md#bootstrap-audit-semantics).

### Audit Resource Types

`resource_type` is the snake_case form of one of the values in `AuditResourceType`: `query_request`, `datasource`, `user`, `permission`, `review_plan`, `notification_channel`, `ai_config`, `custom_jdbc_driver`, `system_smtp`, `user_invitation`, `organization`, `oauth2_config`, `saml_config`, `langfuse_config`, `audit_log`, `slack_app_config`, `access_grant_request`, `routing_policy`.

---

## bootstrap_state

Per-resource fingerprint cache used by the env-driven `bootstrap` reconciler to detect "no change" between the new spec and the previously persisted state, so a restart with unchanged env vars writes zero new rows to `audit_log`. Added in V41 ([AF-196](https://github.com/bablsoft/accessflow/issues/196)).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | UUID — references `organizations(id)` semantically; no SQL FK so the row survives org deletion (matches the audit-module convention from V14) |
| `resource_type` | VARCHAR(100) — one of the `BootstrapResourceType` enum names: `ORGANIZATION`, `ADMIN_USER`, `NOTIFICATION_CHANNEL`, `AI_CONFIG`, `REVIEW_PLAN`, `DATASOURCE`, `SAML_CONFIG`, `OAUTH2_CONFIG`, `SYSTEM_SMTP` |
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
