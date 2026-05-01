CREATE TYPE query_type_enum    AS ENUM ('SELECT', 'INSERT', 'UPDATE', 'DELETE', 'DDL', 'OTHER');
CREATE TYPE query_status_enum  AS ENUM ('PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'EXECUTED', 'FAILED', 'CANCELLED');

CREATE TABLE query_requests (
    id                      UUID               PRIMARY KEY,
    datasource_id           UUID               NOT NULL REFERENCES datasources(id),
    submitted_by            UUID               NOT NULL REFERENCES users(id),
    sql_text                TEXT               NOT NULL,
    query_type              query_type_enum    NOT NULL,
    status                  query_status_enum  NOT NULL DEFAULT 'PENDING_AI',
    justification           TEXT,
    ai_analysis_id          UUID,              -- FK to ai_analyses added in V7 (circular ref)
    execution_started_at    TIMESTAMPTZ,
    execution_completed_at  TIMESTAMPTZ,
    rows_affected           BIGINT,
    error_message           TEXT,
    execution_duration_ms   INTEGER,
    created_at              TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP
);
