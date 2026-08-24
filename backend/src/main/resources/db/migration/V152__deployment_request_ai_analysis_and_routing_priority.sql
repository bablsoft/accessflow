-- #691 (epic #682): the deployment request pipeline — AI analysis keying and deterministic routing.
--
-- 1) ai_analyses keys off exactly one target. #691 adds a fourth: a deployment request. This is the
--    same widening V106 performed for request_group_item_id, so every governed surface's analyses
--    live on one table and the admin AI-analyses surface can read them uniformly.
--    NOTE: this does NOT put deployment analyses inside the monthly token budget. That aggregate
--    (AiAnalysisStatsRepository.sumTokensSince) inner-joins query_requests, so api_request_id,
--    request_group_item_id and now deployment_request_id rows all contribute zero — a pre-existing
--    AF-500 / AF-501 gap, deliberately not widened here because it would change every org's budget
--    behaviour in a sub-issue that is not about rate limiting.
ALTER TABLE ai_analyses ADD COLUMN deployment_request_id UUID
    REFERENCES deployment_requests(id) ON DELETE CASCADE;

ALTER TABLE ai_analyses DROP CONSTRAINT chk_ai_analyses_target;
ALTER TABLE ai_analyses ADD CONSTRAINT chk_ai_analyses_target
    CHECK (num_nonnulls(query_request_id, api_request_id, request_group_item_id,
                        deployment_request_id) = 1);

-- V101 / V106 left api_request_id and request_group_item_id unindexed; index this one so deleting a
-- pipeline (which cascades to its deployment requests) does not seq-scan ai_analyses.
CREATE INDEX idx_ai_analyses_deployment_request ON ai_analyses (deployment_request_id);

-- 2) Routing priority must be unique per organization so "first enabled match by ascending
--    priority" is deterministic. Mirrors uq_routing_policy_org_priority (V59). The service returns
--    409 DEPLOYMENT_ROUTING_POLICY_PRIORITY_CONFLICT ahead of this; the index is the concurrency
--    backstop. Safe to add unconditionally: #684/#688 shipped no write path for
--    deployment_routing_policies, so the table is empty in every deployment.
CREATE UNIQUE INDEX uq_deployment_routing_policies_org_priority
    ON deployment_routing_policies (organization_id, priority);
