-- AF-623: automated sensitive-data discovery & classification scanning. A scheduled scanner
-- samples column data through the existing per-engine sampling path, detects sensitive data with
-- regex + checksum detectors (and optionally the org's bound AI analyzer), and proposes
-- data-classification tags (AF-447) that an admin confirms or dismisses.

CREATE TYPE discovery_detector AS ENUM ('EMAIL', 'CREDIT_CARD', 'SSN', 'IBAN', 'PHONE', 'AI');

CREATE TYPE discovery_finding_status AS ENUM ('PENDING', 'CONFIRMED', 'DISMISSED');

-- Per-datasource opt-in + cadence. The scheduled job drains this table (enabled rows whose
-- last_scan_at is older than scan_interval_hours), so no cross-org datasource enumeration is
-- needed. One row per datasource; absence = discovery disabled with defaults.
CREATE TABLE discovery_scan_config (
    id                        UUID        PRIMARY KEY,
    organization_id           UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    datasource_id             UUID        NOT NULL UNIQUE REFERENCES datasources(id) ON DELETE CASCADE,
    enabled                   BOOLEAN     NOT NULL DEFAULT FALSE,
    sample_size               INT         NOT NULL DEFAULT 100,
    scan_interval_hours       INT         NOT NULL DEFAULT 24,
    ai_classification_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    last_scan_at              TIMESTAMPTZ,
    last_scan_error           TEXT,
    version                   BIGINT      NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dsc_org ON discovery_scan_config (organization_id);

-- Proposed classification worklist. One row per (column, classification, detector); rescans
-- refresh PENDING rows in place and never touch CONFIRMED/DISMISSED rows (a dismissal is a
-- permanent suppression for future scans). sample_redacted only ever holds a redacted value —
-- raw sampled data never persists.
CREATE TABLE discovery_finding (
    id                UUID                     PRIMARY KEY,
    organization_id   UUID                     NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    datasource_id     UUID                     NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    schema_name       TEXT,
    table_name        TEXT                     NOT NULL,
    column_name       TEXT                     NOT NULL,
    classification    data_classification      NOT NULL,
    detector          discovery_detector       NOT NULL,
    confidence        INT                      NOT NULL,
    sample_redacted   TEXT,
    rationale         TEXT,
    match_count       INT                      NOT NULL DEFAULT 0,
    sample_count      INT                      NOT NULL DEFAULT 0,
    status            discovery_finding_status NOT NULL DEFAULT 'PENDING',
    decided_by        UUID                     REFERENCES users(id) ON DELETE SET NULL,
    decided_at        TIMESTAMPTZ,
    first_detected_at TIMESTAMPTZ              NOT NULL,
    last_detected_at  TIMESTAMPTZ              NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Natural key for rescan upserts. schema_name NULL (engines without schemas) would make a plain
-- UNIQUE treat two rows as distinct; COALESCE collapses NULL to '' (same pattern as V90).
CREATE UNIQUE INDEX uq_discovery_finding ON discovery_finding
    (organization_id, datasource_id, COALESCE(schema_name, ''), table_name, column_name,
     classification, detector);

-- Worklist scan (status filter) per datasource.
CREATE INDEX idx_df_ds_status ON discovery_finding (datasource_id, status);
