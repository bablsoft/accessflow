-- Deployment break-glass retro-review (#692, epic #682). Widen break_glass_events to a third
-- target kind, mirroring the AF-500 widening in V101: a break-glass event now targets EXACTLY ONE
-- of a query request, an API request, or a deployment request. pipeline_id is a bare UUID (no FK),
-- like connector_id, so deleting a pipeline never erases the retro-review record; the request FK
-- cascades exactly as api_request_id does.
ALTER TABLE break_glass_events ADD COLUMN deployment_request_id UUID UNIQUE
    REFERENCES deployment_requests(id) ON DELETE CASCADE;
ALTER TABLE break_glass_events ADD COLUMN pipeline_id UUID;
ALTER TABLE break_glass_events DROP CONSTRAINT chk_break_glass_target;
ALTER TABLE break_glass_events ADD CONSTRAINT chk_break_glass_target
    CHECK (num_nonnulls(query_request_id, api_request_id, deployment_request_id) = 1);
