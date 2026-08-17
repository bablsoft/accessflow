-- #622 (part 2): idle-reviewer escalation and nudge reminders.
--
-- An approval chain stalls silently today: a request sits in PENDING_REVIEW until the hard
-- approval_timeout_hours auto-rejects it, with nothing in between. These two per-plan knobs add the
-- warning shots — escalate to the next stage's approvers and org admins, and re-nudge the reviewers
-- who have not acted yet.
--
-- Both are nullable and null means OFF, so every existing plan keeps exactly today's behaviour on
-- upgrade. They are plain INT hours to match approval_timeout_hours; the codebase confines Duration
-- to @ConfigurationProperties and never puts it on an entity.
--
-- Escalation is NOTIFY-ONLY. Neither column widens who may approve — idleness must never become a
-- way to bypass the configured approver set, which is exactly the sort of thing an auditor asks
-- about. The eligibility path does not read either column.

ALTER TABLE review_plans ADD COLUMN escalation_after_hours INT;
ALTER TABLE review_plans ADD COLUMN nudge_interval_hours   INT;

COMMENT ON COLUMN review_plans.escalation_after_hours IS
    'Hours a request may sit in PENDING_REVIEW before ReviewEscalationJob notifies the next stage '
    'and org admins. NULL disables escalation for the plan. Notify-only: never widens eligibility.';
COMMENT ON COLUMN review_plans.nudge_interval_hours IS
    'Hours between reminder notifications to reviewers who have not yet decided. NULL disables '
    'nudges for the plan.';

-- Escalation state lives on the request, not the plan — it is per-request progress, and stamping it
-- is what makes the job idempotent across replicas and restarts.
ALTER TABLE query_requests ADD COLUMN escalated_at   TIMESTAMPTZ;
ALTER TABLE query_requests ADD COLUMN last_nudged_at TIMESTAMPTZ;

ALTER TABLE api_requests   ADD COLUMN escalated_at   TIMESTAMPTZ;
ALTER TABLE api_requests   ADD COLUMN last_nudged_at TIMESTAMPTZ;

ALTER TABLE request_groups ADD COLUMN escalated_at   TIMESTAMPTZ;
ALTER TABLE request_groups ADD COLUMN last_nudged_at TIMESTAMPTZ;

-- The job scans only rows still awaiting review, so the partial indexes stay tiny regardless of how
-- much history the table accumulates. created_at is the escalation clock, measured from submission
-- exactly like approval_timeout_hours already is — so escalate-after is always read against the
-- same baseline as the timeout it is meant to precede; last_nudged_at is the reminder cursor.
CREATE INDEX idx_query_requests_escalation_scan
    ON query_requests (created_at)
    WHERE status = 'PENDING_REVIEW' AND escalated_at IS NULL;

CREATE INDEX idx_query_requests_nudge_scan
    ON query_requests (last_nudged_at, created_at)
    WHERE status = 'PENDING_REVIEW';

CREATE INDEX idx_api_requests_escalation_scan
    ON api_requests (created_at)
    WHERE status = 'PENDING_REVIEW' AND escalated_at IS NULL;

CREATE INDEX idx_api_requests_nudge_scan
    ON api_requests (last_nudged_at, created_at)
    WHERE status = 'PENDING_REVIEW';

CREATE INDEX idx_request_groups_escalation_scan
    ON request_groups (created_at)
    WHERE status = 'PENDING_REVIEW' AND escalated_at IS NULL;

CREATE INDEX idx_request_groups_nudge_scan
    ON request_groups (last_nudged_at, created_at)
    WHERE status = 'PENDING_REVIEW';
