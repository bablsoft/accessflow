-- Maps an AccessFlow user to a Slack workspace user id (AF-362). Populated by the
-- `/accessflow link <code>` slash-command flow: the user generates a one-time code in
-- AccessFlow, pastes it in Slack, and the verified slash command persists the mapping.
-- Inbound approve/reject callbacks resolve the Slack user back to the AccessFlow user here.
CREATE TABLE user_slack_mapping (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    user_id         UUID        NOT NULL UNIQUE REFERENCES users(id),
    slack_user_id   VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, slack_user_id)
);
