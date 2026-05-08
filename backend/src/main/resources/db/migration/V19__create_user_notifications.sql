CREATE TABLE user_notifications (
    id               UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id  UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    event_type       VARCHAR(64)  NOT NULL,
    query_request_id UUID         REFERENCES query_requests(id) ON DELETE CASCADE,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    is_read          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at          TIMESTAMPTZ
);

CREATE INDEX idx_user_notifications_user_created
    ON user_notifications(user_id, created_at DESC);

CREATE INDEX idx_user_notifications_user_unread
    ON user_notifications(user_id) WHERE is_read = FALSE;
