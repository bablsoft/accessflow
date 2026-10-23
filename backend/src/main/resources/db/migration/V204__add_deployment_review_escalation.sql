-- #985: idle-reviewer escalation and nudge reminders for deployment reviews.
--
-- The deployment twin of V144 (#622): until now a deployment request sat in PENDING_REVIEW until
-- DeploymentTimeoutJob auto-rejected it, with no warning in between. The plan knobs
-- (review_plans.escalation_after_hours / nudge_interval_hours) already exist; this adds the
-- per-request cursors DeploymentReviewEscalationJob stamps so it stays idempotent across replicas
-- and restarts.
--
-- Escalation is NOTIFY-ONLY. Neither column widens who may approve a deployment; the eligibility
-- and decision paths never read them.

ALTER TABLE deployment_requests ADD COLUMN escalated_at   TIMESTAMPTZ;
ALTER TABLE deployment_requests ADD COLUMN last_nudged_at TIMESTAMPTZ;

-- created_at is the escalation clock, the same baseline the timeout scan uses, so escalate-after is
-- always read against the timeout it is meant to precede.
--
-- The nudge scan gets no index on last_nudged_at: its predicate is
-- COALESCE(last_nudged_at, created_at) + interval < now, which is not sargable on that column, so
-- leading with it would buy nothing and cost a write on every stamp. The partial status predicate
-- is what bounds the scan.
CREATE INDEX idx_deployment_requests_escalation_scan
    ON deployment_requests (created_at)
    WHERE status = 'PENDING_REVIEW' AND escalated_at IS NULL;

CREATE INDEX idx_deployment_requests_nudge_scan
    ON deployment_requests (created_at)
    WHERE status = 'PENDING_REVIEW';
