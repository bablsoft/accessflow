-- #1009 (epic #1008): organization-defined SQL review rules. A custom rule is a condition tree over
-- facts derived from the parsed statement, evaluated exactly like a built-in. rule_id carries a
-- mandatory custom_ prefix so it can never collide with a code-defined rule id and every consumer
-- recognises it without a lookup. Rulesets configure custom rules through the existing
-- sql_review_rule_configs.rule_id VARCHAR — no FK, the column is shared with built-in ids.

CREATE TYPE sql_rule_category AS ENUM ('STATEMENT_SAFETY', 'PERFORMANCE', 'SCHEMA_CHANGE', 'DATA_PROTECTION');

CREATE TABLE sql_review_custom_rules (
    id               UUID                PRIMARY KEY,
    organization_id  UUID                NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    rule_id          VARCHAR(100)        NOT NULL,
    name             VARCHAR(255)        NOT NULL,
    description      TEXT,
    message          VARCHAR(500)        NOT NULL,
    category         sql_rule_category   NOT NULL,
    default_severity sql_review_severity NOT NULL,
    condition        JSONB               NOT NULL,
    enabled          BOOLEAN             NOT NULL DEFAULT TRUE,
    version          BIGINT              NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sql_review_custom_rules_rule_id CHECK (rule_id ~ '^custom_[a-z][a-z0-9_]{2,60}$'),
    CONSTRAINT uq_sql_review_custom_rules_org_rule UNIQUE (organization_id, rule_id)
);
