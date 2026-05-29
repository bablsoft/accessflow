-- Per-organization Slack app configuration (AF-362). Holds the bot token + signing secret
-- (AES-256-GCM encrypted at rest), the Slack app id used to route inbound interactive-component
-- and slash-command callbacks, and the default channel for outbound review-request messages.
CREATE TABLE slack_app_config (
    id                       UUID         PRIMARY KEY,
    organization_id          UUID         NOT NULL UNIQUE REFERENCES organizations(id),
    app_id                   VARCHAR(64)  NOT NULL UNIQUE,
    bot_token_encrypted      TEXT         NOT NULL,
    signing_secret_encrypted TEXT         NOT NULL,
    default_channel_id       VARCHAR(64)  NOT NULL,
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX slack_app_config_active_idx ON slack_app_config(organization_id) WHERE active;
