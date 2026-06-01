-- AF-381: conditional / role-based dynamic data masking policies. Named, per-column
-- masking policies bound to a datasource. Each policy carries a masking strategy and an
-- optional reveal condition (role / group / user list) evaluated per query submitter:
-- a submitter in reveal_to sees the unmasked value, everyone else gets the strategy output.
-- This enhances today's static restricted_columns masking (which keeps its "***" behaviour
-- when no policy covers a column). Strategy parameters (e.g. partial visible-suffix length)
-- live in strategy_params JSONB. Enforcement runs at result-read time in the proxy module,
-- before serialization and before the result snapshot is persisted, so unmasked values
-- never persist.

CREATE TYPE masking_strategy AS ENUM ('FULL', 'PARTIAL', 'HASH', 'EMAIL', 'FORMAT_PRESERVING');

CREATE TABLE masking_policy (
    id                  UUID             PRIMARY KEY,
    organization_id     UUID             NOT NULL REFERENCES organizations(id),
    datasource_id       UUID             NOT NULL REFERENCES datasources(id),
    column_ref          TEXT             NOT NULL,
    strategy            masking_strategy NOT NULL,
    strategy_params     JSONB            NOT NULL DEFAULT '{}',
    reveal_to_roles     TEXT[],
    reveal_to_group_ids UUID[],
    reveal_to_user_ids  UUID[],
    enabled             BOOLEAN          NOT NULL DEFAULT true,
    version             BIGINT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Backs the per-execution resolution scan (enabled policies for one datasource).
CREATE INDEX idx_masking_policy_ds_enabled
    ON masking_policy (organization_id, datasource_id, enabled);
