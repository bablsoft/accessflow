-- #861 (epic #860): deterministic SQL review — persistence foundation. A datasource may carry an
-- environment; a ruleset binds one environment (or, with environment NULL, acts as the
-- organization-wide default) to a set of per-rule severities; findings record which rules a
-- submitted query violated. Existing datasources stay NULL and resolve to the org default.
-- Rule ids are code-defined (the catalog lands in #862), so rule_id is VARCHAR, not a PG enum.
-- No rows are seeded and nothing evaluates yet — this migration only has to leave
-- ddl-auto=validate satisfied.

CREATE TYPE datasource_environment AS ENUM ('DEVELOPMENT', 'TEST', 'STAGING', 'PRODUCTION');
CREATE TYPE sql_review_severity    AS ENUM ('OFF', 'WARN', 'BLOCK');

-- Nullable, no default: an unset environment is a legitimate state.
ALTER TABLE datasources ADD COLUMN environment datasource_environment;

CREATE TABLE sql_review_rulesets (
    id              UUID                   PRIMARY KEY,
    organization_id UUID                   NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(255)           NOT NULL,
    description     TEXT,
    environment     datasource_environment,
    enabled         BOOLEAN                NOT NULL DEFAULT TRUE,
    version         BIGINT                 NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ            NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- At most one ruleset per environment per organization, plus at most one org-wide default
-- (environment IS NULL). Two partial indexes because NULLs never collide in a plain UNIQUE.
CREATE UNIQUE INDEX uq_sql_review_rulesets_org_env
    ON sql_review_rulesets (organization_id, environment)
    WHERE environment IS NOT NULL;
CREATE UNIQUE INDEX uq_sql_review_rulesets_org_default
    ON sql_review_rulesets (organization_id)
    WHERE environment IS NULL;

CREATE TABLE sql_review_rule_configs (
    id         UUID                PRIMARY KEY,
    ruleset_id UUID                NOT NULL REFERENCES sql_review_rulesets(id) ON DELETE CASCADE,
    rule_id    VARCHAR(100)        NOT NULL,
    severity   sql_review_severity NOT NULL,
    params     JSONB,
    version    BIGINT              NOT NULL DEFAULT 0,
    CONSTRAINT uq_sql_review_rule_configs_ruleset_rule UNIQUE (ruleset_id, rule_id)
);

CREATE INDEX idx_sql_review_rule_configs_ruleset ON sql_review_rule_configs (ruleset_id);

-- Findings are never stored as English text: rule_id + args are rendered per reader's locale.
CREATE TABLE query_sql_review_findings (
    id               UUID                PRIMARY KEY,
    query_request_id UUID                NOT NULL REFERENCES query_requests(id) ON DELETE CASCADE,
    rule_id          VARCHAR(100)        NOT NULL,
    severity         sql_review_severity NOT NULL,
    statement_index  INTEGER             NOT NULL DEFAULT 0,
    line_number      INTEGER,
    args             JSONB,
    created_at       TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_query_sql_review_findings_request ON query_sql_review_findings (query_request_id);
