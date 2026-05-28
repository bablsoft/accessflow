-- AF-361: query result diffing across repeated runs.
-- previous_run_id links a successful run to the most recent prior EXECUTED run
-- with the same canonical SQL submitted by the same user against the same
-- datasource. canonical_sql carries the normalised SQL (comments stripped,
-- whitespace collapsed, upper-cased) used as the lookup key.
ALTER TABLE query_requests
    ADD COLUMN previous_run_id UUID NULL REFERENCES query_requests(id),
    ADD COLUMN canonical_sql   TEXT NULL;

-- Partial index supports the per-execution "find latest EXECUTED run with this
-- canonical SQL" lookup. Restricting to EXECUTED rows with non-null
-- canonical_sql keeps the index small (existing rows have NULL canonical_sql
-- after the migration and never match).
CREATE INDEX idx_query_requests_diff_lookup
    ON query_requests (submitted_by, datasource_id, canonical_sql, execution_completed_at DESC)
    WHERE status = 'EXECUTED' AND canonical_sql IS NOT NULL;
