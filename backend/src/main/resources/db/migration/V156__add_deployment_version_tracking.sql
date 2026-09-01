-- AF-741: multi-environment deployment version tracking (epic AF-682). Free-form tags on
-- deployment environments (the query_templates.tags precedent — a customer-specific target is
-- simply its own environment row tagged accordingly), plus a one-row-per-environment read model
-- of the currently deployed version. The table is a current/previous projection maintained by
-- the deploygov version tracker inside the EXECUTED/outcome transactions — deployment history
-- stays fully derived from deployment_requests, and nothing here feeds back into gate,
-- approval, or routing decisions.

ALTER TABLE deployment_environments
    ADD COLUMN tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

CREATE INDEX idx_deployment_environments_tags_gin
    ON deployment_environments USING GIN (tags);

-- organization_id, pipeline_id and the request ids are bare UUIDs per deploygov convention;
-- environment_id keeps a real FK because the row is meaningless without its environment and
-- must go with it, like the other per-environment configuration tables from V149.
CREATE TABLE deployment_environment_versions (
    id                   UUID         PRIMARY KEY,
    organization_id      UUID         NOT NULL,
    pipeline_id          UUID         NOT NULL,
    environment_id       UUID         NOT NULL UNIQUE
                             REFERENCES deployment_environments(id) ON DELETE CASCADE,
    current_version      VARCHAR(255),
    current_request_id   UUID,
    deployed_at          TIMESTAMPTZ,
    previous_version     VARCHAR(255),
    previous_request_id  UUID,
    previous_deployed_at TIMESTAMPTZ,
    last_outcome         deployment_outcome,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version_lock         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_deployment_environment_versions_org_pipeline
    ON deployment_environment_versions (organization_id, pipeline_id);
