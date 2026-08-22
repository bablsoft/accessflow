-- #684 (epic #682): Deployment approval governance — definitions. A new `deploygov` module gates
-- CI/CD deployments behind AccessFlow approval workflows. This migration lands the definition
-- side: deployment pipelines (per-org CI/CD targets), their promotion environments with
-- per-environment review overrides, freeze windows (one-off or recurring), and the per-user /
-- per-group trigger grants mirroring api_connector_user_permissions / api_connector_group_permissions
-- (AF-530). The request pipeline (deployment_requests, review decisions, routing) follows in the
-- next migration.
--
-- Cross-module references (organization_id / review_plan_id / ai_config_id / user_id / created_by)
-- are bare UUIDs (no FK), like api_connectors / access_grant_request / audit_log, so deploygov rows
-- survive after the referenced aggregate is deleted. The group-permission table keeps the real FKs
-- of its V111 template.

CREATE TYPE pipeline_provider AS ENUM (
    'GITHUB_ACTIONS', 'GITLAB_CI', 'AZURE_PIPELINES', 'JENKINS', 'CIRCLECI',
    'BITBUCKET_PIPELINES', 'GENERIC'
);
CREATE TYPE freeze_behavior AS ENUM ('HOLD', 'REJECT');

-- Governed CI/CD pipelines (per org). `project_ref` is the provider-side project identifier
-- (owner/repo, GitLab project path/id, Azure org/project, Jenkins job path, ...).
CREATE TABLE deployment_pipelines (
    id                   UUID              PRIMARY KEY,
    organization_id      UUID              NOT NULL,
    name                 VARCHAR(255)      NOT NULL,
    provider             pipeline_provider NOT NULL,
    repository_url       TEXT,
    project_ref          TEXT,
    review_plan_id       UUID,
    ai_analysis_enabled  BOOLEAN           NOT NULL DEFAULT TRUE,
    ai_config_id         UUID,
    is_active            BOOLEAN           NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_deployment_pipelines_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_deployment_pipelines_org ON deployment_pipelines (organization_id, is_active);

-- Promotion environments per pipeline (dev -> staging -> production, ordered by sort_order).
-- required_approvals / review_plan_id are nullable per-environment overrides of the pipeline's
-- review configuration.
CREATE TABLE deployment_environments (
    id                  UUID         PRIMARY KEY,
    pipeline_id         UUID         NOT NULL REFERENCES deployment_pipelines(id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL,
    sort_order          INTEGER      NOT NULL DEFAULT 0,
    require_review      BOOLEAN      NOT NULL DEFAULT TRUE,
    required_approvals  INTEGER,
    review_plan_id      UUID,
    allow_break_glass   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_deployment_environments_pipeline_name UNIQUE (pipeline_id, name)
);

-- Deployment freeze windows. Scope: pipeline_id and/or environment_id, both NULL = org-wide.
-- Shape: exactly one of a one-off window (starts_at + ends_at) or a recurring window
-- (days_of_week + start_time + end_time + timezone). days_of_week uses ISO-8601 numbering
-- (1 = Monday .. 7 = Sunday); start/end times are wall-clock in `timezone`.
CREATE TABLE deployment_freeze_windows (
    id               UUID            PRIMARY KEY,
    organization_id  UUID            NOT NULL,
    pipeline_id      UUID            REFERENCES deployment_pipelines(id) ON DELETE CASCADE,
    environment_id   UUID            REFERENCES deployment_environments(id) ON DELETE CASCADE,
    starts_at        TIMESTAMPTZ,
    ends_at          TIMESTAMPTZ,
    days_of_week     SMALLINT[],
    start_time       TIME,
    end_time         TIME,
    timezone         VARCHAR(64),
    behavior         freeze_behavior NOT NULL,
    reason           TEXT,
    enabled          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_deployment_freeze_window_shape CHECK (
        (num_nonnulls(starts_at, ends_at) = 2
            AND num_nonnulls(days_of_week, start_time, end_time, timezone) = 0)
        OR (num_nonnulls(starts_at, ends_at) = 0
            AND num_nonnulls(days_of_week, start_time, end_time, timezone) = 4)
    )
);

CREATE INDEX idx_deployment_freeze_windows_org ON deployment_freeze_windows (organization_id, enabled);

-- "Share pipeline trigger rights with the team" — per user, per pipeline (mirror of
-- api_connector_user_permissions). can_trigger gates submitting a deployment request;
-- can_break_glass gates the emergency path (AF-385 mirror, wired in a later sub-issue).
CREATE TABLE deployment_pipeline_user_permissions (
    id               UUID        PRIMARY KEY,
    pipeline_id      UUID        NOT NULL REFERENCES deployment_pipelines(id) ON DELETE CASCADE,
    user_id          UUID        NOT NULL,
    can_trigger      BOOLEAN     NOT NULL DEFAULT FALSE,
    can_break_glass  BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at       TIMESTAMPTZ,
    created_by       UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_deployment_pipeline_user_perms UNIQUE (pipeline_id, user_id)
);

CREATE INDEX idx_deployment_pipeline_user_perms_user ON deployment_pipeline_user_permissions (user_id);

-- Group-inherited trigger grants (mirror of api_connector_group_permissions, AF-530).
CREATE TABLE deployment_pipeline_group_permissions (
    id               UUID        PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    pipeline_id      UUID        NOT NULL REFERENCES deployment_pipelines(id) ON DELETE CASCADE,
    group_id         UUID        NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    can_trigger      BOOLEAN     NOT NULL DEFAULT FALSE,
    can_break_glass  BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at       TIMESTAMPTZ,
    created_by       UUID        NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_deployment_pipeline_group_perms UNIQUE (pipeline_id, group_id)
);

CREATE INDEX idx_deployment_pipeline_group_perms_pipeline
    ON deployment_pipeline_group_permissions (pipeline_id);
