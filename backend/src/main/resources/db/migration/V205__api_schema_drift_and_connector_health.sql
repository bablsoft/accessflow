-- #891 (epic #890): storage foundation for API schema drift detection and scheduled connector
-- health. Revisions are append-only and hash-deduplicated; the current catalog on api_schemas is
-- never replaced by a job. Refresh and health are opt-in per row — a NULL interval means off, so
-- existing rows are untouched. organization_id / accepted_by / resolved_by are bare UUIDs (no FK),
-- the apigov convention from V100.

CREATE TYPE api_schema_drift_kind AS ENUM (
    'OPERATION_ADDED', 'OPERATION_REMOVED', 'SHAPE_CHANGED', 'DEPRECATED', 'CLASSIFICATION_FLIPPED'
);
CREATE TYPE api_schema_drift_status AS ENUM ('OPEN', 'ACCEPTED', 'DISMISSED');
CREATE TYPE api_connector_health_status AS ENUM ('UNKNOWN', 'HEALTHY', 'UNHEALTHY');

ALTER TABLE api_schemas
    ADD COLUMN refresh_interval_minutes INTEGER,
    ADD COLUMN current_revision_id UUID,
    ADD COLUMN last_refresh_at TIMESTAMPTZ,
    ADD COLUMN last_refresh_error TEXT;

ALTER TABLE api_connectors
    ADD COLUMN health_check_interval_minutes INTEGER,
    ADD COLUMN last_probe_status api_connector_health_status NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN last_probe_at TIMESTAMPTZ,
    ADD COLUMN consecutive_probe_failures INTEGER NOT NULL DEFAULT 0;

CREATE TABLE api_schema_revisions (
    id                UUID PRIMARY KEY,
    schema_id         UUID        NOT NULL REFERENCES api_schemas (id) ON DELETE CASCADE,
    connector_id      UUID        NOT NULL,
    content_hash      CHAR(64)    NOT NULL,
    raw_content       TEXT,
    parsed_operations JSONB       NOT NULL DEFAULT '[]',
    operation_count   INTEGER     NOT NULL DEFAULT 0,
    fetched_at        TIMESTAMPTZ NOT NULL,
    last_seen_at      TIMESTAMPTZ NOT NULL,
    accepted_at       TIMESTAMPTZ,
    accepted_by       UUID,
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_api_schema_revisions_hash UNIQUE (schema_id, content_hash)
);

CREATE INDEX idx_api_schema_revisions_schema ON api_schema_revisions (schema_id, fetched_at DESC);

CREATE TABLE api_schema_drift_findings (
    id                   UUID PRIMARY KEY,
    organization_id      UUID                    NOT NULL,
    connector_id         UUID                    NOT NULL,
    schema_id            UUID                    NOT NULL REFERENCES api_schemas (id) ON DELETE CASCADE,
    revision_id          UUID                    NOT NULL REFERENCES api_schema_revisions (id) ON DELETE CASCADE,
    kind                 api_schema_drift_kind   NOT NULL,
    operation_id         VARCHAR(512)            NOT NULL,
    detail               JSONB                   NOT NULL DEFAULT '{}',
    affected_grant_count INTEGER                 NOT NULL DEFAULT 0,
    status               api_schema_drift_status NOT NULL DEFAULT 'OPEN',
    created_at           TIMESTAMPTZ             NOT NULL,
    resolved_at          TIMESTAMPTZ,
    resolved_by          UUID,
    version              BIGINT                  NOT NULL DEFAULT 0
);

CREATE INDEX idx_api_schema_drift_findings_connector
    ON api_schema_drift_findings (connector_id, status, created_at DESC);

CREATE TABLE api_connector_health_checks (
    id           UUID PRIMARY KEY,
    connector_id UUID        NOT NULL REFERENCES api_connectors (id) ON DELETE CASCADE,
    checked_at   TIMESTAMPTZ NOT NULL,
    success      BOOLEAN     NOT NULL,
    message      TEXT,
    duration_ms  INTEGER     NOT NULL DEFAULT 0
);

CREATE INDEX idx_api_connector_health_checks_connector
    ON api_connector_health_checks (connector_id, checked_at DESC);
