-- #881 (epic #870): schema drift scan configuration. One opt-in row per deploygov pipeline.
--
-- The drift job drains this table rather than enumerating deploygov environments, because deploygov
-- exposes no cross-organization pipeline or environment listing: DeploymentPipelineLookupService and
-- DeploymentEnvironmentLookupService are both scoped to a pipeline the caller already knows. This is
-- the discovery_scan_config precedent (V129) — "the scheduled job drains this table, so no cross-org
-- enumeration is needed".
--
-- enabled defaults to FALSE: drift opens connections to customer databases on a timer, so an upgrade
-- must never start scanning an estate nobody asked it to scan. Absence of a row means the same thing.
--
-- Cross-module references (organization_id / pipeline_id / baseline_environment_id) are bare UUIDs
-- with no FK, the schemachange and deploygov convention from V149 and V178.

CREATE TABLE schema_drift_configs (
    id                      UUID                  PRIMARY KEY,
    organization_id         UUID                  NOT NULL,
    pipeline_id             UUID                  NOT NULL,
    enabled                 BOOLEAN               NOT NULL DEFAULT FALSE,
    baseline                schema_drift_baseline NOT NULL DEFAULT 'PREVIOUS_ENVIRONMENT',
    -- Only read when baseline = 'BASELINE_ENVIRONMENT'; must be an environment of this pipeline that
    -- binds a datasource. Validated on write and re-checked at scan time (the environment can move).
    baseline_environment_id UUID,
    scan_interval_hours     INTEGER               NOT NULL DEFAULT 24,
    last_scan_at            TIMESTAMPTZ,
    last_scan_error         TEXT,
    version                 BIGINT                NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_schema_drift_configs_pipeline UNIQUE (pipeline_id)
);

-- The job's drain query reads only enabled rows, which are the minority.
CREATE INDEX idx_schema_drift_configs_enabled ON schema_drift_configs (enabled) WHERE enabled;
