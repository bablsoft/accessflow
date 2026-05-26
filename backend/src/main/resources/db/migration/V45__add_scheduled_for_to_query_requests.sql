ALTER TABLE query_requests
    ADD COLUMN scheduled_for TIMESTAMPTZ;

CREATE INDEX idx_query_requests_scheduled_for
    ON query_requests(scheduled_for)
    WHERE scheduled_for IS NOT NULL;
