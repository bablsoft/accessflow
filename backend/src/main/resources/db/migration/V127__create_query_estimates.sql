CREATE TABLE query_estimates (
    id                 UUID          PRIMARY KEY,
    query_request_id   UUID          NOT NULL UNIQUE REFERENCES query_requests(id) ON DELETE CASCADE,
    engine_id          VARCHAR(64),
    query_type         query_type,
    supported          BOOLEAN       NOT NULL DEFAULT false,
    estimated_rows     BIGINT,
    affected_row_count BIGINT,
    scan_type          VARCHAR(128),
    estimated_cost     DOUBLE PRECISION,
    plan               JSONB,
    raw_plan           TEXT,
    unsupported_reason VARCHAR(500),
    failed             BOOLEAN       NOT NULL DEFAULT false,
    error_message      VARCHAR(500),
    duration_ms        INTEGER,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
