-- Supports the datasource health dashboard aggregate (AF-365): per-datasource counts and
-- execution-duration percentiles over a trailing 24h window, filtered by datasource_id + created_at.
CREATE INDEX idx_query_requests_datasource_created_at
    ON query_requests (datasource_id, created_at);
