-- #984: the instant a deployment request entered PENDING_REVIEW — the approval-latency and
-- timeout-rate axis of the deployment metrics. Stamped by DeploymentRequestStateService.
-- Deliberately not backfilled: the instant is unknown for older rows, and a guessed value would
-- corrupt the latency percentiles.
ALTER TABLE deployment_requests ADD COLUMN review_started_at TIMESTAMPTZ;

COMMENT ON COLUMN deployment_requests.review_started_at IS
    'When the request entered PENDING_REVIEW (#984); NULL for rows that never did or pre-date the column';
