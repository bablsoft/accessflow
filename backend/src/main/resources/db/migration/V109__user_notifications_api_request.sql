-- AF-529: in-app notifications for API requests (AF-500) reused user_notifications.query_request_id
-- for the API-request id, violating its FK to query_requests. Give user_notifications its own
-- api_request_id column (mirroring the V101 pattern on ai_analyses / break_glass_events) and constrain
-- a row to reference at most one of the two (most notifications — anomaly, digest, attestation,
-- connector, erasure — reference neither).

ALTER TABLE user_notifications
    ADD COLUMN api_request_id UUID REFERENCES api_requests(id) ON DELETE CASCADE;

ALTER TABLE user_notifications
    ADD CONSTRAINT chk_user_notifications_target
    CHECK (num_nonnulls(query_request_id, api_request_id) <= 1);
