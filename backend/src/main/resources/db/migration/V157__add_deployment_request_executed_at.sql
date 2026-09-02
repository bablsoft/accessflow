-- AF-742: version inventory & drift API (epic AF-682). The drift metric deployments_behind
-- counts distinct versions successfully deployed after an environment's current deploy, which
-- needs the actual execution instant — created_at systematically understates drift for requests
-- that sat in review, and updated_at moves on every later write. Stamped by
-- DeploymentRequestStateService on the APPROVED → EXECUTED transition, in the same transaction
-- (and off the same Clock) as the #741 version tracker's deployed_at.

ALTER TABLE deployment_requests
    ADD COLUMN executed_at TIMESTAMPTZ;

-- Backfill executed rows (including the ones a FAILED outcome later flipped to FAILED) with the
-- best deterministic upper bound of execution time: the outcome report when one exists,
-- otherwise the row's last write.
UPDATE deployment_requests
   SET executed_at = COALESCE(outcome_reported_at, updated_at)
 WHERE status = 'EXECUTED'::query_status
    OR (status = 'FAILED'::query_status AND outcome IS NOT NULL);

-- Serves the per-pipeline grouped drift projection (version, max(executed_at)).
CREATE INDEX idx_deployment_requests_executed_versions
    ON deployment_requests (pipeline_id, version)
    WHERE executed_at IS NOT NULL;
