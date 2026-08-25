-- AF-695: in-app notifications for deployment requests (epic AF-682). Mirrors the V109 pattern —
-- deployment notifications must not reuse query_request_id (FK to query_requests) or
-- api_request_id (FK to api_requests), so user_notifications gets its own deployment_request_id
-- column, and the at-most-one-target constraint widens to cover all three.

ALTER TABLE user_notifications
    ADD COLUMN deployment_request_id UUID REFERENCES deployment_requests(id) ON DELETE CASCADE;

ALTER TABLE user_notifications
    DROP CONSTRAINT chk_user_notifications_target;

ALTER TABLE user_notifications
    ADD CONSTRAINT chk_user_notifications_target
    CHECK (num_nonnulls(query_request_id, api_request_id, deployment_request_id) <= 1);
