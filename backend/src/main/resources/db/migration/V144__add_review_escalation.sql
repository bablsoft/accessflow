-- #622 (part 2): idle-reviewer escalation and nudge reminders.
--
-- An approval chain stalls silently today: a request sits in PENDING_REVIEW until the hard
-- approval_timeout_hours auto-rejects it, with nothing in between. These two per-plan knobs add the
-- warning shots — escalate to the reviewers the request is currently blocked on plus every org
-- admin, and re-nudge those same reviewers on a cadence. "Currently blocked on" is the plan's
-- current stage (core.api.ReviewStages), not its first: a request that has been idle for hours may
-- already have cleared stage 1, and reminding the people who did act while ignoring the ones who
-- have not is the exact failure this is meant to prevent.
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
    'Hours a request may sit in PENDING_REVIEW before ReviewEscalationJob notifies the reviewers '
    'at its current stage plus every org admin. NULL disables escalation for the plan. '
    'Notify-only: never widens eligibility.';
COMMENT ON COLUMN review_plans.nudge_interval_hours IS
    'Hours between reminder notifications to the reviewers at the request''s current stage. '
    'NULL disables nudges for the plan.';

-- Escalation state lives on the request, not the plan — it is per-request progress, and stamping it
-- is what makes the job idempotent across replicas and restarts.
ALTER TABLE query_requests ADD COLUMN escalated_at   TIMESTAMPTZ;
ALTER TABLE query_requests ADD COLUMN last_nudged_at TIMESTAMPTZ;

ALTER TABLE api_requests   ADD COLUMN escalated_at   TIMESTAMPTZ;
ALTER TABLE api_requests   ADD COLUMN last_nudged_at TIMESTAMPTZ;

-- request_groups gets escalated_at but deliberately no last_nudged_at: the requestgroups module
-- has no notification path, so a nudge cursor there could only be written, never acted on.
ALTER TABLE request_groups ADD COLUMN escalated_at   TIMESTAMPTZ;

-- The job scans only rows still awaiting review, so the partial indexes stay tiny regardless of how
-- much history the table accumulates. created_at is the escalation clock, measured from submission
-- exactly like approval_timeout_hours already is — so escalate-after is always read against the
-- same baseline as the timeout it is meant to precede.
--
-- The nudge scans get no index on last_nudged_at: their predicate is
-- COALESCE(last_nudged_at, created_at) + interval < now, which is not sargable on that column, so
-- leading with it would buy nothing and cost a write on every stamp. The partial status predicate
-- is what bounds the scan. api_requests and request_groups already have an equivalent partial index
-- (V101, V106), so only query_requests needs one added here.
CREATE INDEX idx_query_requests_escalation_scan
    ON query_requests (created_at)
    WHERE status = 'PENDING_REVIEW' AND escalated_at IS NULL;

CREATE INDEX idx_query_requests_nudge_scan
    ON query_requests (created_at)
    WHERE status = 'PENDING_REVIEW';

CREATE INDEX idx_api_requests_escalation_scan
    ON api_requests (created_at)
    WHERE status = 'PENDING_REVIEW' AND escalated_at IS NULL;

CREATE INDEX idx_request_groups_escalation_scan
    ON request_groups (created_at)
    WHERE status = 'PENDING_REVIEW' AND escalated_at IS NULL;
