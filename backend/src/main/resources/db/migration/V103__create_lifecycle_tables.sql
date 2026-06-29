-- AF-499: Data Lifecycle Manager. Retention policies let an admin declare a per-datasource retention
-- window + action (HARD_DELETE / SOFT_DELETE / PSEUDONYMIZE) over a table/column-set/classification
-- tag; a scheduled scan stages lifecycle runs. Right-to-erasure deletion requests flow through an
-- AI-assisted + human-approved workflow mirroring the query-review lifecycle. lifecycle_runs is the
-- tamper-evident execution ledger backing the activity view + compliance reporting. lifecycle_salt
-- holds the per-org pseudonymization salt (AES-256-GCM encrypted), with a version for rotation.
--
-- organization_id / datasource_id / created_by / requested_by / subject columns are bare UUIDs (no FK),
-- like attestation_item / break_glass_events / audit_log, so the records survive as a frozen evidence
-- trail even after the source row is deleted. The single real FK is decision/run -> request/policy.

CREATE TYPE lifecycle_action AS ENUM ('HARD_DELETE', 'SOFT_DELETE', 'PSEUDONYMIZE');
CREATE TYPE lifecycle_transform AS ENUM ('SHA256_SALTED', 'FORMAT_PRESERVING', 'TOKENIZATION');
CREATE TYPE lifecycle_subject_type AS ENUM ('USER_ID', 'EMAIL', 'CUSTOM');
CREATE TYPE erasure_status AS ENUM (
    'PENDING_SCOPE_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED', 'FAILED', 'CANCELLED'
);
CREATE TYPE erasure_decision AS ENUM ('APPROVED', 'REJECTED');
CREATE TYPE lifecycle_run_kind AS ENUM ('RETENTION_POLICY', 'ERASURE_REQUEST');
CREATE TYPE lifecycle_run_status AS ENUM ('STAGED', 'EXECUTING', 'COMPLETED', 'FAILED');

CREATE TABLE retention_policies (
    id                 UUID                PRIMARY KEY,
    organization_id    UUID                NOT NULL,
    datasource_id      UUID                NOT NULL,
    name               TEXT                NOT NULL,
    description        TEXT,
    -- Target: a table, plus an optional column set and/or a classification tag. At least target_table
    -- or classification_tag must be present (app-enforced in the service layer).
    target_table       TEXT,
    target_columns     TEXT[]              NOT NULL DEFAULT '{}',
    classification_tag TEXT,
    -- The timestamp column the retention window is measured against (e.g. created_at, last_activity).
    timestamp_column   TEXT                NOT NULL,
    -- ISO-8601 period (e.g. P30D, P7Y) parsed to an interval at scan time.
    retention_window   TEXT                NOT NULL,
    action             lifecycle_action    NOT NULL,
    -- Required only when action = PSEUDONYMIZE; the read-time transform applied to aged values.
    transform_type     lifecycle_transform,
    enabled            BOOLEAN             NOT NULL DEFAULT TRUE,
    -- Marker column written by SOFT_DELETE policies; defaults to deleted_at when null.
    soft_delete_column TEXT,
    created_by         UUID                NOT NULL,
    version            BIGINT              NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Admin policy list: org-scoped, newest first.
CREATE INDEX idx_retention_policies_org
    ON retention_policies (organization_id, created_at DESC);

-- Scan job: enabled policies only.
CREATE INDEX idx_retention_policies_enabled
    ON retention_policies (organization_id, datasource_id) WHERE enabled = TRUE;

-- Proxy directive resolution: which policies target a given datasource.
CREATE INDEX idx_retention_policies_datasource
    ON retention_policies (datasource_id) WHERE enabled = TRUE;

CREATE TABLE deletion_requests (
    id                 UUID                PRIMARY KEY,
    organization_id    UUID                NOT NULL,
    datasource_id      UUID                NOT NULL,
    -- The data subject the erasure targets, e.g. a user id or email.
    subject_type       lifecycle_subject_type NOT NULL,
    subject_identifier TEXT                NOT NULL,
    status             erasure_status      NOT NULL DEFAULT 'PENDING_SCOPE_AI',
    reason             TEXT,
    requested_by       UUID                NOT NULL,
    -- FK-less reference to the ai_analyses row produced by erasure scope detection (nullable until AI runs).
    ai_scope_analysis_id UUID,
    -- Immutable scope snapshot captured before execution: matched tables, predicates, estimated rows.
    scope_snapshot     JSONB,
    estimated_rows     BIGINT,
    affected_rows      BIGINT,
    executed_at        TIMESTAMPTZ,
    failure_reason     TEXT,
    version            BIGINT              NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Admin erasure queue: org-scoped, status-filtered, newest first.
CREATE INDEX idx_deletion_requests_org_status
    ON deletion_requests (organization_id, status, created_at DESC);

-- Review queue scan: requests awaiting human review.
CREATE INDEX idx_deletion_requests_pending_review
    ON deletion_requests (organization_id) WHERE status = 'PENDING_REVIEW';

CREATE TABLE deletion_request_decisions (
    id          UUID              PRIMARY KEY,
    request_id  UUID              NOT NULL REFERENCES deletion_requests(id) ON DELETE CASCADE,
    reviewer_id UUID              NOT NULL,
    stage       INT               NOT NULL DEFAULT 1,
    decision    erasure_decision  NOT NULL,
    comment     TEXT,
    created_at  TIMESTAMPTZ       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- One decision per (request, reviewer, stage): idempotent replay backstop.
    CONSTRAINT uq_deletion_request_decision UNIQUE (request_id, reviewer_id, stage)
);

CREATE INDEX idx_deletion_request_decisions_request
    ON deletion_request_decisions (request_id);

CREATE TABLE lifecycle_runs (
    id                  UUID                 PRIMARY KEY,
    organization_id     UUID                 NOT NULL,
    datasource_id       UUID                 NOT NULL,
    kind                lifecycle_run_kind   NOT NULL,
    -- Exactly one of policy_id / deletion_request_id is set per the kind (app-enforced).
    policy_id           UUID,
    deletion_request_id UUID,
    status              lifecycle_run_status NOT NULL DEFAULT 'STAGED',
    action              lifecycle_action     NOT NULL,
    -- Tables touched + per-table affected-row counts, captured for the activity view + compliance report.
    matched_tables      JSONB                NOT NULL DEFAULT '[]',
    affected_rows       BIGINT               NOT NULL DEFAULT 0,
    -- HARD_DELETE / SOFT_DELETE / PSEUDONYMIZE(transform); free text method label for the proof record.
    method              TEXT,
    failure_reason      TEXT,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    version             BIGINT               NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ          NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Activity/history view + compliance report: org-scoped, newest first.
CREATE INDEX idx_lifecycle_runs_org_created
    ON lifecycle_runs (organization_id, created_at DESC);

-- Scan job: STAGED runs awaiting execution.
CREATE INDEX idx_lifecycle_runs_staged
    ON lifecycle_runs (organization_id) WHERE status = 'STAGED';

CREATE TABLE lifecycle_salt (
    organization_id UUID        PRIMARY KEY,
    -- Per-org pseudonymization salt/pepper, AES-256-GCM encrypted (never serialized). Rotated by
    -- bumping version; old values stay hashed under the previous salt (irreversible by design).
    salt_encrypted  TEXT        NOT NULL,
    version          INT        NOT NULL DEFAULT 1,
    rotated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
