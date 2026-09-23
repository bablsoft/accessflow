-- #882 (epic #870): an in-app notification may target a schema change promotion. Mirrors V155:
-- the new nullable FK column, then chk_user_notifications_target re-created wide enough to keep
-- every target mutually exclusive — a row names at most one of a query request, an API request,
-- a deployment request, or a schema change promotion.
ALTER TABLE user_notifications
    ADD COLUMN schema_change_promotion_id UUID REFERENCES schema_change_set_promotions(id) ON DELETE CASCADE;
ALTER TABLE user_notifications
    DROP CONSTRAINT chk_user_notifications_target;
ALTER TABLE user_notifications
    ADD CONSTRAINT chk_user_notifications_target
    CHECK (num_nonnulls(query_request_id, api_request_id, deployment_request_id,
                        schema_change_promotion_id) <= 1);
