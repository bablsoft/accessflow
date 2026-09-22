-- #878 (epic #870): Schema change governance — persistence foundation. A new `schemachange`
-- module authors DDL change sets once and promotes them along a deploygov pipeline's environment
-- ladder as ordered request groups, with a scheduled drift job comparing each environment's live
-- schema against a baseline. This migration only lays the storage: nothing reads or writes these
-- tables until #879 (authoring), #880 (promotion) and #881 (drift) — it has to leave
-- ddl-auto=validate satisfied and nothing else.
--
-- Cross-module references (organization_id / pipeline_id / environment_id / datasource_id /
-- request_group_id / created_by / promoted_by) are bare UUIDs (no FK), the deploygov convention
-- from V149, so a change set and its promotion history survive deletion of the aggregate they
-- name. Only the intra-module parent -> child links carry a real ON DELETE CASCADE.

CREATE TYPE schema_change_set_status       AS ENUM ('DRAFT', 'ACTIVE', 'ARCHIVED');
CREATE TYPE schema_change_promotion_status AS ENUM (
    'PENDING', 'IN_REVIEW', 'APPROVED', 'APPLIED', 'FAILED', 'PARTIALLY_APPLIED', 'CANCELLED'
);
CREATE TYPE schema_drift_baseline          AS ENUM (
    'PREVIOUS_ENVIRONMENT', 'BASELINE_ENVIRONMENT', 'PROMOTION_SNAPSHOT'
);
CREATE TYPE schema_drift_finding_kind      AS ENUM (
    'MISSING_IN_TARGET', 'UNEXPECTED_IN_TARGET', 'TYPE_MISMATCH', 'NULLABILITY_MISMATCH',
    'PRIMARY_KEY_MISMATCH', 'FOREIGN_KEY_MISMATCH'
);
CREATE TYPE schema_drift_finding_status    AS ENUM ('OPEN', 'ACKNOWLEDGED', 'RESOLVED');

CREATE TABLE schema_change_sets (
    id                  UUID                     PRIMARY KEY,
    organization_id     UUID                     NOT NULL,
    pipeline_id         UUID                     NOT NULL,
    name                VARCHAR(255)             NOT NULL,
    description         TEXT,
    status              schema_change_set_status NOT NULL DEFAULT 'DRAFT',
    -- SHA-256 hex over the ordered statement text; NULL until #879 computes it.
    statements_checksum CHAR(64),
    created_by          UUID,
    version             BIGINT                   NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_schema_change_sets_org_pipeline_name UNIQUE (organization_id, pipeline_id, name)
);

CREATE TABLE schema_change_set_statements (
    id             UUID        PRIMARY KEY,
    change_set_id  UUID        NOT NULL REFERENCES schema_change_sets(id) ON DELETE CASCADE,
    sequence_order INTEGER     NOT NULL,
    sql_text       TEXT        NOT NULL,
    query_type     query_type  NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_schema_change_set_statements_order UNIQUE (change_set_id, sequence_order)
);

CREATE TABLE schema_change_set_promotions (
    id                  UUID                           PRIMARY KEY,
    organization_id     UUID                           NOT NULL,
    change_set_id       UUID                           NOT NULL REFERENCES schema_change_sets(id) ON DELETE CASCADE,
    environment_id      UUID                           NOT NULL,
    datasource_id       UUID                           NOT NULL,
    request_group_id    UUID,
    status              schema_change_promotion_status NOT NULL DEFAULT 'PENDING',
    -- The change set's checksum at submission: evidence of exactly what was sent.
    statements_checksum CHAR(64)                       NOT NULL,
    promoted_by         UUID,
    submitted_at        TIMESTAMPTZ                    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at          TIMESTAMPTZ,
    error_message       TEXT,
    -- The post-apply introspection (DatabaseSchemaView) the PROMOTION_SNAPSHOT drift baseline reads.
    schema_snapshot     JSONB,
    snapshot_taken_at   TIMESTAMPTZ,
    version             BIGINT                         NOT NULL DEFAULT 0
);

CREATE INDEX idx_schema_change_set_promotions_set_env ON schema_change_set_promotions (change_set_id, environment_id);
CREATE INDEX idx_schema_change_set_promotions_group   ON schema_change_set_promotions (request_group_id);

-- At most one non-terminal promotion per change set per environment. PENDING / IN_REVIEW /
-- APPROVED are the non-terminal states (SchemaChangePromotionStatus.isTerminal() mirrors this
-- list); APPLIED / FAILED / PARTIALLY_APPLIED / CANCELLED rows never collide, so a new attempt is
-- only possible once the previous one has ended.
CREATE UNIQUE INDEX uq_schema_change_set_promotions_open
    ON schema_change_set_promotions (change_set_id, environment_id)
    WHERE status IN ('PENDING', 'IN_REVIEW', 'APPROVED');

CREATE TABLE schema_drift_scans (
    id              UUID                  PRIMARY KEY,
    organization_id UUID                  NOT NULL,
    pipeline_id     UUID                  NOT NULL,
    environment_id  UUID                  NOT NULL,
    datasource_id   UUID                  NOT NULL,
    baseline        schema_drift_baseline NOT NULL,
    started_at      TIMESTAMPTZ           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMPTZ,
    -- FALSE when the engine samples rather than reads a catalog (Redis, MongoDB) and was not diffed.
    applicable      BOOLEAN               NOT NULL DEFAULT TRUE,
    findings_count  INTEGER               NOT NULL DEFAULT 0,
    -- TRUE when the table cap or time budget cut the scan short.
    partial         BOOLEAN               NOT NULL DEFAULT FALSE,
    error_message   TEXT
);

CREATE INDEX idx_schema_drift_scans_org_env_started ON schema_drift_scans (organization_id, environment_id, started_at DESC);

CREATE TABLE schema_drift_findings (
    id                UUID                        PRIMARY KEY,
    organization_id   UUID                        NOT NULL,
    scan_id           UUID                        NOT NULL REFERENCES schema_drift_scans(id) ON DELETE CASCADE,
    environment_id    UUID                        NOT NULL,
    object_path       VARCHAR(1024)               NOT NULL,
    finding_kind      schema_drift_finding_kind   NOT NULL,
    expected_value    TEXT,
    actual_value      TEXT,
    status            schema_drift_finding_status NOT NULL DEFAULT 'OPEN',
    first_detected_at TIMESTAMPTZ                 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at      TIMESTAMPTZ                 NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at       TIMESTAMPTZ
);

CREATE INDEX idx_schema_drift_findings_org_status_seen ON schema_drift_findings (organization_id, status, last_seen_at DESC);
-- Postgres does not index FK columns: the scan cascade and the per-scan findings read both walk scan_id.
CREATE INDEX idx_schema_drift_findings_scan            ON schema_drift_findings (scan_id);
