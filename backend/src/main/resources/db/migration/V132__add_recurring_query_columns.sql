-- #627: recurring approved queries. A parent APPROVED query carries a recurrence rule
-- (6-field Spring cron in UTC, or an ISO-8601 duration) with a mandatory expiry; each
-- occurrence is a child query_requests row created directly in APPROVED with
-- submission_reason = 'RECURRING' and recurring_parent_id pointing at the series parent.
-- recurrence_next_run_at is the scheduler cursor (advanced atomically with each occurrence
-- insert); recurrence_halted_reason is set only on a fail-closed halt.
ALTER TABLE query_requests
    ADD COLUMN recurrence_rule          TEXT,
    ADD COLUMN recurrence_until         TIMESTAMPTZ,
    ADD COLUMN recurrence_next_run_at   TIMESTAMPTZ,
    ADD COLUMN recurrence_halted_reason TEXT,
    ADD COLUMN recurring_parent_id      UUID REFERENCES query_requests(id);

-- RecurringQueryRunJob poll: due parents only, mirrors idx_query_requests_scheduled_for (V45)
CREATE INDEX idx_query_requests_recurrence_due
    ON query_requests (recurrence_next_run_at)
    WHERE recurrence_next_run_at IS NOT NULL;

-- Occurrence history (GET /queries/{id}/occurrences): children by parent, newest first
CREATE INDEX idx_query_requests_recurring_parent
    ON query_requests (recurring_parent_id, created_at DESC)
    WHERE recurring_parent_id IS NOT NULL;
