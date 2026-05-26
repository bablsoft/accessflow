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
| `restricted_columns` | TEXT[] nullable — fully-qualified `schema.table.column` entries whose values are masked in SELECT results before persistence and surfaced to the AI analyzer; null/empty means no column restrictions |
| `expires_at` | TIMESTAMPTZ nullable — time-limited access grants |
| `created_by` | FK → `users` |
| `created_at` | TIMESTAMPTZ |

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
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

### Status Transitions

```
PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTED
                           ↘ REJECTED   (manual reviewer rejection)
                           ↘ TIMED_OUT  (approval-timeout auto-reject, see review_plans → Approval timeout)
           ↘ PENDING_REVIEW (if no AI)
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
| `ai_provider` | ENUM: `OPENAI` \| `ANTHROPIC` \| `OLLAMA` |
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
| `provider` | ENUM `ai_provider`: `OPENAI` \| `ANTHROPIC` \| `OLLAMA` |
| `model` | VARCHAR(100) — provider-specific model name |
| `endpoint` | VARCHAR(500) nullable — base URL. Honored only when `provider = OLLAMA`; ignored at runtime for OpenAI and Anthropic (Spring AI's built-in default endpoints are used). The column remains nullable for back-compat — pre-existing values on OpenAI/Anthropic rows are preserved on the wire but have no runtime effect. |
| `api_key_encrypted` | TEXT nullable — AES-256-GCM ciphertext; `@JsonIgnore` |
| `timeout_ms` | INTEGER — call timeout, CHECK 1000–600000 |
| `max_prompt_tokens` | INTEGER — CHECK 100–200000 |
| `max_completion_tokens` | INTEGER — CHECK 100–200000 |
| `version` | BIGINT — optimistic locking |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

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
| `AI_CONFIG_UPDATED` | Admin updates an `ai_config` row via `PUT /admin/ai-configs/{id}`. Metadata includes only the fields that changed (`old_provider`, `new_provider`, `old_model`, `new_model`, `old_name`, `new_name`, `api_key_changed`). |
| `AI_CONFIG_DELETED` | Admin deletes an `ai_config` row via `DELETE /admin/ai-configs/{id}`. |
| `ORGANIZATION_CREATED` | Emitted by the env-driven bootstrap reconciler when it provisions a brand-new organization. Metadata: `source: "BOOTSTRAP"`, `change_kind: "CREATE"`, `name`, `slug`. |
| `NOTIFICATION_CHANNEL_CREATED` / `NOTIFICATION_CHANNEL_UPDATED` | Emitted by the bootstrap reconciler when it creates or updates a `notification_channels` row from `accessflow.bootstrap.notificationChannels[*]`. Metadata: `source: "BOOTSTRAP"`, `change_kind`, `name`, `channel_type`, optional `changed_fields`. |
| `NOTIFICATION_DELIVERY_EXHAUSTED` | Emitted by the notifications dispatcher after a webhook channel exhausts its retry budget (1 initial attempt + 3 scheduled retries at +30 s / +2 min / +10 min). Resource: `notification_channel`, `actor_id = NULL`. Metadata: `source: "DISPATCHER"`, `channel_id`, `channel_type`, `event_type`, `attempt_count`, optional `last_http_status`, optional `last_error` (truncated to 500 chars). Other channels (Slack/Discord/Teams/Telegram/Email) are not yet audited on exhaustion. |
| `OAUTH2_CONFIG_UPDATED` | Emitted by the bootstrap reconciler when it applies a per-provider OAuth2 config from `accessflow.bootstrap.oauth2[*]`. Metadata: `source: "BOOTSTRAP"`, `change_kind: "UPDATE"`, `provider`, `config_type: "oauth2"`, optional `changed_fields`. |
| `SAML_CONFIG_UPDATED` | Emitted by the bootstrap reconciler when it applies the SAML configuration from `accessflow.bootstrap.saml`. Metadata: `source: "BOOTSTRAP"`, `change_kind: "UPDATE"`, `config_type: "saml"`, optional `changed_fields`. |

Bootstrap reuses the existing `*_CREATED` / `*_UPDATED` actions for `DATASOURCE`, `AI_CONFIG`, `REVIEW_PLAN`, `USER`, and `SYSTEM_SMTP_UPDATED` — `metadata.source = "BOOTSTRAP"` plus `metadata.change_kind` is what distinguishes a bootstrap-driven write from an admin-UI-driven one. See [docs/05-backend.md → "Bootstrap audit semantics"](05-backend.md#bootstrap-audit-semantics).

### Audit Resource Types

`resource_type` is the snake_case form of one of the values in `AuditResourceType`: `query_request`, `datasource`, `user`, `permission`, `review_plan`, `notification_channel`, `ai_config`, `custom_jdbc_driver`, `system_smtp`, `user_invitation`, `organization`, `oauth2_config`, `saml_config`.

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

Stores notification channel configurations (email, Slack, webhook, Discord, Telegram, Microsoft Teams).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `channel_type` | ENUM: `EMAIL` \| `SLACK` \| `WEBHOOK` \| `DISCORD` \| `TELEGRAM` \| `MS_TEAMS` |
| `name` | VARCHAR(255) — human label |
| `config` | JSONB — channel-specific config (sensitive fields AES-encrypted) |
| `is_active` | BOOLEAN DEFAULT true |
| `created_at` | TIMESTAMPTZ |

Sensitive `config` fields encrypted with AES-256-GCM and masked on read:

- `EMAIL` → `smtp_password` → `smtp_password_encrypted`
- `WEBHOOK` → `secret` → `secret_encrypted`
- `TELEGRAM` → `bot_token` → `bot_token_encrypted`

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
| `default_role` | ENUM `user_role_type` — role assigned to users JIT-provisioned by this provider. Defaults to `ANALYST`. |
| `active` | BOOLEAN NOT NULL DEFAULT FALSE — only active providers appear on the login page. Activating a `GITHUB` or `GITHUB_ENTERPRISE` row with a non-empty `allowed_organizations` is rejected unless `scopes_override` contains `read:org`. Activating an `OIDC` row requires `display_name`, `authorization_uri`, `token_uri`, `user_info_uri`, `jwk_set_uri`, and `issuer_uri` to be set. Activating a `GITHUB_ENTERPRISE` or `GITLAB_ENTERPRISE` row requires `base_url` to be a valid `https://` origin. |
| `version` | BIGINT — `@Version` optimistic lock |
| `created_at` / `updated_at` | TIMESTAMPTZ |

Unique constraint: `(organization_id, provider)`. Partial index on `(organization_id)` where `active` for the public providers endpoint.

---

## Database Indexes (Key)

```sql
-- Query requests: common filter patterns
CREATE INDEX idx_query_requests_status ON query_requests(status);
CREATE INDEX idx_query_requests_datasource ON query_requests(datasource_id);
CREATE INDEX idx_query_requests_submitter ON query_requests(submitted_by);
CREATE INDEX idx_query_requests_created ON query_requests(created_at DESC);

-- Audit log: time-range queries
CREATE INDEX idx_audit_log_created ON audit_log(organization_id, created_at DESC);
CREATE INDEX idx_audit_log_actor ON audit_log(actor_id, created_at DESC);
CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);

-- Permissions: lookup by user+datasource
CREATE UNIQUE INDEX idx_permissions_user_ds ON datasource_user_permissions(user_id, datasource_id);
```
