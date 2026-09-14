-- #864: submission enforcement for deterministic SQL review rules. Request-group members (AF-501)
-- are request_group_items rows, not query_requests rows, and are evaluated on their own submission
-- path — so a finding must be able to key off either. Same widening ai_analyses got in V106.
ALTER TABLE query_sql_review_findings ALTER COLUMN query_request_id DROP NOT NULL;
ALTER TABLE query_sql_review_findings ADD COLUMN request_group_item_id UUID
    REFERENCES request_group_items(id) ON DELETE CASCADE;
ALTER TABLE query_sql_review_findings ADD CONSTRAINT chk_query_sql_review_findings_target
    CHECK (num_nonnulls(query_request_id, request_group_item_id) = 1);

CREATE INDEX idx_query_sql_review_findings_group_item
    ON query_sql_review_findings (request_group_item_id);
