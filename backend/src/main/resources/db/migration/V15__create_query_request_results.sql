CREATE TABLE query_request_results (
    query_request_id UUID         PRIMARY KEY REFERENCES query_requests(id) ON DELETE CASCADE,
    columns          JSONB        NOT NULL,
    rows             JSONB        NOT NULL,
    row_count        BIGINT       NOT NULL,
    truncated        BOOLEAN      NOT NULL DEFAULT FALSE,
    duration_ms      INTEGER      NOT NULL,
    recorded_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
