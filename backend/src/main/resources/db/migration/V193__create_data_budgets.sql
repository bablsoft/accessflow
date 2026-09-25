-- #942: per-user data-volume budgets. A data_budget bounds how many result rows and/or result bytes
-- EACH targeted user may read from one datasource over a rolling window (window_minutes, trailing
-- from now — no reset job, no timezone). Scoping mirrors row_limit_policy: all three applies_to_*
-- arrays empty ⇒ every user of the datasource; a group target gives every member their own
-- allowance, never a shared pool. When several budgets match, each is evaluated on its own window
-- and the most constrained one wins; REJECT beats REQUIRE_REVIEW once exhausted.

CREATE TYPE data_budget_breach_action AS ENUM ('REJECT', 'REQUIRE_REVIEW');
CREATE TYPE data_budget_usage_source AS ENUM ('QUERY', 'REQUEST_GROUP', 'SAMPLE_DATA');

CREATE TABLE data_budgets (
    id                     UUID                      PRIMARY KEY,
    organization_id        UUID                      NOT NULL REFERENCES organizations(id),
    datasource_id          UUID                      NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    name                   VARCHAR(120)              NOT NULL,
    max_rows               BIGINT                    CHECK (max_rows > 0),
    max_bytes              BIGINT                    CHECK (max_bytes > 0),
    window_minutes         INTEGER                   NOT NULL DEFAULT 1440
                               CHECK (window_minutes BETWEEN 60 AND 44640),
    breach_action          data_budget_breach_action NOT NULL DEFAULT 'REQUIRE_REVIEW',
    warn_threshold_percent SMALLINT                  CHECK (warn_threshold_percent BETWEEN 1 AND 99),
    applies_to_roles       TEXT[],
    applies_to_group_ids   UUID[],
    applies_to_user_ids    UUID[],
    enabled                BOOLEAN                   NOT NULL DEFAULT true,
    version                BIGINT                    NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ               NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_data_budgets_has_limit CHECK (max_rows IS NOT NULL OR max_bytes IS NOT NULL)
);

CREATE INDEX idx_data_budgets_ds_enabled ON data_budgets (organization_id, datasource_id, enabled);

-- Append-only usage ledger: one row per delivered SELECT result (query execution, request-group
-- member, sample-data read). rows_read / bytes_read are what the user actually received — after
-- row-security filtering and every cap. Pruned by DataBudgetUsagePruneJob past the longest window.
-- query_request_id / request_group_id are bare provenance UUIDs (no FK) so a later erasure or
-- retention delete of the source never blocks on the ledger.
CREATE TABLE data_budget_usage (
    id               UUID                     PRIMARY KEY,
    organization_id  UUID                     NOT NULL,
    user_id          UUID                     NOT NULL,
    datasource_id    UUID                     NOT NULL,
    rows_read        BIGINT                   NOT NULL CHECK (rows_read >= 0),
    bytes_read       BIGINT                   NOT NULL CHECK (bytes_read >= 0),
    source           data_budget_usage_source NOT NULL,
    query_request_id UUID,
    request_group_id UUID,
    occurred_at      TIMESTAMPTZ              NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Backs the trailing-window SUM (user, datasource, occurred_at >= now - window): an index range
-- scan bounded by one user's reads on one datasource inside the window.
CREATE INDEX idx_data_budget_usage_user_ds_time
    ON data_budget_usage (user_id, datasource_id, occurred_at);
-- Backs the prune job's range delete.
CREATE INDEX idx_data_budget_usage_occurred_at ON data_budget_usage (occurred_at);
