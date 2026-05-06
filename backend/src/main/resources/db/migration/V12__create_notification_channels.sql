CREATE TYPE notification_channel_type AS ENUM ('EMAIL', 'SLACK', 'WEBHOOK');

CREATE TABLE notification_channels (
    id              UUID                       PRIMARY KEY,
    organization_id UUID                       NOT NULL REFERENCES organizations(id),
    channel_type    notification_channel_type  NOT NULL,
    name            VARCHAR(255)               NOT NULL,
    config          JSONB                      NOT NULL DEFAULT '{}'::jsonb,
    is_active       BOOLEAN                    NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ                NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_channels_org
    ON notification_channels(organization_id);

CREATE INDEX idx_notification_channels_org_active
    ON notification_channels(organization_id) WHERE is_active;
