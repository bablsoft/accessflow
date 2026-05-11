# 03 — Data Model

All entities are stored in AccessFlow's **internal PostgreSQL database**. Customer databases are never written to except through the proxied, approved query path.

> **Naming convention:** All tables use `snake_case`. All primary keys are `UUID`. All timestamps are `TIMESTAMPTZ`.

---

## organizations

Represents a tenant. In Community Edition there is always exactly one organization.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `name` | VARCHAR(255) NOT NULL |
| `slug` | VARCHAR(100) UNIQUE — URL-safe identifier |
| `edition` | ENUM: `COMMUNITY` \| `ENTERPRISE` |
| `saml_config_id` | FK → `saml_configurations` (nullable, Enterprise only) |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

---

## users

Platform users. Can be created locally or auto-provisioned via SAML (Enterprise).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `email` | VARCHAR(255) UNIQUE NOT NULL |
| `display_name` | VARCHAR(255) |
| `password_hash` | VARCHAR — null if SSO-only user |
| `auth_provider` | ENUM: `LOCAL` \| `SAML` |
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

## datasources

A customer database that AccessFlow proxies. Credentials are stored encrypted.

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `name` | VARCHAR(255) — human-readable label; **UNIQUE per organization** (case-insensitive at the service layer) |
| `db_type` | ENUM: `POSTGRESQL` \| `MYSQL` |
| `host` | VARCHAR(255) |
| `port` | INTEGER |
| `database_name` | VARCHAR(255) |
| `username` | VARCHAR(255) — service account username |
| `password_encrypted` | TEXT — AES-256-GCM encrypted at rest |
| `ssl_mode` | ENUM: `DISABLE` \| `REQUIRE` \| `VERIFY_CA` \| `VERIFY_FULL` |
| `connection_pool_size` | INTEGER DEFAULT 10 |
| `max_rows_per_query` | INTEGER DEFAULT 1000 — hard cap on SELECT result rows. Surfaced to the proxy module via `DatasourceConnectionDescriptor.maxRowsPerQuery` and clamped at execution time to `accessflow.proxy.execution.max-rows`. |
| `require_review_reads` | BOOLEAN DEFAULT false — force review even for SELECT |
| `require_review_writes` | BOOLEAN DEFAULT true — force review for INSERT/UPDATE/DELETE |
| `review_plan_id` | FK → `review_plans` |
| `ai_analysis_enabled` | BOOLEAN DEFAULT true |
| `is_active` | BOOLEAN DEFAULT true |
| `created_at` | TIMESTAMPTZ |

> **Constraint:** `UNIQUE (organization_id, name)` — added in `V10__datasource_unique_name_per_org.sql`. Attempting to create or rename a datasource into an existing name in the same organization returns HTTP 409 with `error: DATASOURCE_NAME_ALREADY_EXISTS`.

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
| `sql_text` | TEXT — the raw submitted SQL |
| `query_type` | ENUM: `SELECT` \| `INSERT` \| `UPDATE` \| `DELETE` \| `DDL` \| `OTHER` |
| `status` | ENUM: `PENDING_AI` \| `PENDING_REVIEW` \| `APPROVED` \| `REJECTED` \| `TIMED_OUT` \| `EXECUTED` \| `FAILED` \| `CANCELLED` |
| `justification` | TEXT nullable — requester's stated reason for the query |
| `ai_analysis_id` | FK → `ai_analyses` nullable |
| `execution_started_at` | TIMESTAMPTZ nullable |
| `execution_completed_at` | TIMESTAMPTZ nullable |
| `rows_affected` | BIGINT nullable |
| `error_message` | TEXT nullable |
| `execution_duration_ms` | INTEGER nullable |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

### Status Transitions

```
PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTED
                           ↘ REJECTED   (manual reviewer rejection)
                           ↘ TIMED_OUT  (approval-timeout auto-reject, see review_plans → Approval timeout)
           ↘ PENDING_REVIEW (if no AI)
PENDING_REVIEW → CANCELLED (by submitter)
APPROVED → FAILED (on execution error)
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

Per-organization AI analyzer settings. One row per organization (uniqueness on
`organization_id`). The active `AiAnalyzerStrategy` delegate is built on demand by
`AiAnalyzerStrategyHolder` from this row; changes are picked up at runtime via an
`AiConfigUpdatedEvent`. See [docs/05-backend.md → "AI Query Analyzer Service"](05-backend.md#ai-query-analyzer-service).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations`, UNIQUE |
| `provider` | ENUM `ai_provider`: `OPENAI` \| `ANTHROPIC` \| `OLLAMA` |
| `model` | VARCHAR(100) — provider-specific model name |
| `endpoint` | VARCHAR(500) nullable — base URL override |
| `api_key_encrypted` | TEXT nullable — AES-256-GCM ciphertext; `@JsonIgnore` |
| `timeout_ms` | INTEGER — call timeout, CHECK 1000–600000 |
| `max_prompt_tokens` | INTEGER — CHECK 100–200000 |
| `max_completion_tokens` | INTEGER — CHECK 100–200000 |
| `enable_ai_default` | BOOLEAN — whether AI runs on submissions by default |
| `auto_approve_low` | BOOLEAN — auto-approve LOW-risk queries |
| `block_critical` | BOOLEAN — auto-reject CRITICAL-risk queries |
| `include_schema` | BOOLEAN — include schema introspection in the prompt |
| `version` | BIGINT — optimistic locking |
| `created_at` | TIMESTAMPTZ |
| `updated_at` | TIMESTAMPTZ |

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
| `AI_CONFIG_UPDATED` | Admin updates `ai_config` row via `PUT /admin/ai-config`. Metadata includes only the fields that changed (`old_provider`, `new_provider`, `old_model`, `new_model`, `api_key_changed`). |

---

## notification_channels

Stores notification channel configurations (email, Slack, webhook).

| Column | Type / Notes |
|--------|-------------|
| `id` | UUID PK |
| `organization_id` | FK → `organizations` |
| `channel_type` | ENUM: `EMAIL` \| `SLACK` \| `WEBHOOK` |
| `name` | VARCHAR(255) — human label |
| `config` | JSONB — channel-specific config (sensitive fields AES-encrypted) |
| `is_active` | BOOLEAN DEFAULT true |
| `created_at` | TIMESTAMPTZ |

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

## saml_configurations *(Enterprise only)*

Stores SAML 2.0 Identity Provider configuration for an organization.

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
