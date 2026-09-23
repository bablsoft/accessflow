-- #934: per-table row limits. A row_limit_policy caps the rows a SELECT may return when it
-- references the policy's table, scoped to the submitters named in applies_to_* (all three empty ⇒
-- every submitter, the row_security_policy polarity). A policy only ever lowers the cap: the proxy
-- takes the minimum of the global cap, datasources.max_rows_per_query, the per-grantee
-- row_limit_override and every matching policy. schema_name NULL matches the table in any schema;
-- names are stored lowercased with quotes stripped, the same normalization as the parser's
-- referenced tables.

CREATE TABLE row_limit_policy (
    id                   UUID         PRIMARY KEY,
    organization_id      UUID         NOT NULL REFERENCES organizations(id),
    datasource_id        UUID         NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    schema_name          VARCHAR(255),
    table_name           VARCHAR(255) NOT NULL,
    max_rows             INTEGER      NOT NULL CHECK (max_rows > 0),
    applies_to_roles     TEXT[],
    applies_to_group_ids UUID[],
    applies_to_user_ids  UUID[],
    enabled              BOOLEAN      NOT NULL DEFAULT true,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Backs the per-execution resolution scan (enabled policies for one datasource).
CREATE INDEX idx_row_limit_policy_ds_enabled
    ON row_limit_policy (organization_id, datasource_id, enabled);
