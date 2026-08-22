-- #684 (epic #682): Deployment approval governance — request pipeline. deployment_requests mirrors
-- api_requests (reusing the shared query_status / submission_reason enum types verbatim);
-- deployment_review_decisions mirrors api_review_decisions (shared decision enum);
-- deployment_routing_policies mirrors api_routing_policies (shared api_routing_action enum).
-- `version` is the semantic artifact version being deployed ("2.4.1"); the optimistic-lock column
-- is `version_lock` to avoid the collision. `outcome` records what the pipeline reported after the
-- gate opened (EXECUTED means the gate opened and the pipeline confirmed it proceeded).

CREATE TYPE deployment_outcome AS ENUM ('SUCCEEDED', 'FAILED', 'ROLLED_BACK');

CREATE TABLE deployment_requests (
    id                   UUID               PRIMARY KEY,
    pipeline_id          UUID               NOT NULL,
    environment_id       UUID               NOT NULL,
    organization_id      UUID               NOT NULL,
    submitted_by         UUID               NOT NULL,
    version              VARCHAR(255)       NOT NULL,
    commit_sha           VARCHAR(64),
    artifact_ref         TEXT,
    run_url              TEXT,
    external_run_id      TEXT,
    metadata             JSONB              NOT NULL DEFAULT '{}',
    status               query_status       NOT NULL DEFAULT 'PENDING_AI',
    submission_reason    submission_reason  NOT NULL DEFAULT 'USER_SUBMITTED',
    justification        TEXT,
    ai_analysis_id       UUID,
    required_approvals   INTEGER            NOT NULL DEFAULT 1,
    scheduled_for        TIMESTAMPTZ,
    outcome              deployment_outcome,
    outcome_reported_at  TIMESTAMPTZ,
    outcome_detail       TEXT,
    submitted_ip         VARCHAR(45),
    version_lock         BIGINT             NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The gate lookup: (pipeline, version, environment).
CREATE INDEX idx_deployment_requests_pipeline_version
    ON deployment_requests (pipeline_id, version, environment_id);
CREATE INDEX idx_deployment_requests_status ON deployment_requests (status);
CREATE INDEX idx_deployment_requests_org_status
    ON deployment_requests (organization_id, status, created_at DESC);
-- Backs the deferred-run scan (AF-345 mirror, wired in a later sub-issue), like api_requests.
CREATE INDEX idx_deployment_requests_scheduled
    ON deployment_requests (scheduled_for) WHERE status = 'APPROVED' AND scheduled_for IS NOT NULL;
-- Trigger idempotency: a CI job retrying the same run must not create a second request. Partial —
-- requests submitted without an external run id are never deduplicated.
CREATE UNIQUE INDEX uq_deployment_requests_trigger_idem
    ON deployment_requests (pipeline_id, environment_id, version, external_run_id)
    WHERE external_run_id IS NOT NULL;

CREATE TABLE deployment_review_decisions (
    id                     UUID        PRIMARY KEY,
    deployment_request_id  UUID        NOT NULL REFERENCES deployment_requests(id) ON DELETE CASCADE,
    reviewer_id            UUID        NOT NULL,
    decision               decision    NOT NULL,
    comment                TEXT,
    stage                  INTEGER     NOT NULL DEFAULT 1,
    decided_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_deployment_review_decisions_request_reviewer_stage
        UNIQUE (deployment_request_id, reviewer_id, stage)
);

CREATE INDEX idx_deployment_review_decisions_request
    ON deployment_review_decisions (deployment_request_id);

CREATE TABLE deployment_routing_policies (
    id                  UUID               PRIMARY KEY,
    organization_id     UUID               NOT NULL,
    pipeline_id         UUID,
    name                VARCHAR(255)       NOT NULL,
    conditions          JSONB              NOT NULL DEFAULT '{}',
    action              api_routing_action NOT NULL,
    required_approvals  INTEGER,
    priority            INTEGER            NOT NULL DEFAULT 100,
    enabled             BOOLEAN            NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_deployment_routing_policies_org
    ON deployment_routing_policies (organization_id, enabled, priority);
