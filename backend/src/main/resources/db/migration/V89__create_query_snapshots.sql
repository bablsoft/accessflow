-- AF-449: immutable query snapshots & replay. When a query reaches EXECUTED, the workflow module
-- writes one immutable snapshot row here (on QueryExecutedEvent with finalStatus=EXECUTED),
-- capturing the exact, sanitized sql_text, the source datasource's schema fingerprint, the AI
-- analysis verdict, and the approval decisions as they stood at execution time. The row is a
-- forensic/compliance record AND the source artifact for POST /queries/{id}/replay, which re-runs
-- the exact SQL against a chosen test datasource through the full review workflow.
-- Rows are INSERT-only — never mutated. One snapshot per executed query (UNIQUE query_request_id is
-- the idempotency backstop for redelivered events). query_type / db_type reuse the existing PG enums.
-- submitted_by is a bare UUID (no FK) like audit_log.actor_id so deleting the author never erases the
-- immutable record.

CREATE TABLE query_snapshots (
    id                    UUID         PRIMARY KEY,
    query_request_id      UUID         NOT NULL UNIQUE REFERENCES query_requests(id) ON DELETE CASCADE,
    organization_id       UUID         NOT NULL,
    datasource_id         UUID         NOT NULL,
    submitted_by          UUID         NOT NULL,
    sql_text              TEXT         NOT NULL,
    query_type            query_type   NOT NULL,
    transactional         BOOLEAN      NOT NULL DEFAULT FALSE,
    db_type               db_type      NOT NULL,
    referenced_tables     TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    schema_hash           VARCHAR(64),
    ai_analysis           JSONB,
    review_decisions      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    rows_affected         BIGINT,
    execution_duration_ms INTEGER,
    executed_at           TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_query_snapshots_org ON query_snapshots (organization_id);
