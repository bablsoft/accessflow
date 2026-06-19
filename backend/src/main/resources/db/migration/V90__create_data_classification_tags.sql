-- AF-447: data-classification tagging on datasource tables/columns. Tables and columns can be
-- tagged with one or more data classifications (PII, PCI, PHI, GDPR, FINANCIAL, SENSITIVE). Tags
-- are audited, queryable for compliance reporting, and drive automatic derivation of stricter
-- handling: tagging a column auto-applies a masking policy (idempotent), tags raise the AI
-- analyzer's risk score for queries that reference the tagged object, and a read-only derivation
-- preview suggests a stricter review posture. One row per (object, classification) so a single
-- column may carry several classes. column_name NULL = a table-level tag (informational; raises
-- review posture + AI risk but derives no masking, since masking is per-column).

CREATE TYPE data_classification AS ENUM ('PII', 'PCI', 'PHI', 'GDPR', 'FINANCIAL', 'SENSITIVE');

CREATE TABLE data_classification_tag (
    id              UUID                PRIMARY KEY,
    organization_id UUID                NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    datasource_id   UUID                NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    table_name      TEXT                NOT NULL,
    column_name     TEXT,
    classification  data_classification NOT NULL,
    note            TEXT,
    version         BIGINT              NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Per-datasource list / derivation scan.
CREATE INDEX idx_dct_ds ON data_classification_tag (organization_id, datasource_id);
-- Org-wide reporting scan (#459).
CREATE INDEX idx_dct_org ON data_classification_tag (organization_id);

-- Uniqueness across (org, ds, table, column, classification). A plain UNIQUE treats two NULL
-- column_name rows as distinct, which would let duplicate table-level tags through; COALESCE
-- collapses NULL to '' so duplicate table-level tags are rejected too.
CREATE UNIQUE INDEX uq_dct_object_class ON data_classification_tag
    (organization_id, datasource_id, table_name, COALESCE(column_name, ''), classification);
