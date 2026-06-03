-- AF-380: row-level security policies (row filtering). Named, per-datasource row
-- predicates injected into the parsed SQL AST at execute time so a scoped submitter only
-- sees (SELECT) or affects (UPDATE/DELETE) the rows they are authorised for. Each policy
-- targets one table + column and compares it against a value source that is either a
-- literal or a :user.<...> variable (built-ins user.id / user.email / user.role /
-- user.groups, or an admin-set key from users.attributes). The proxy builds the comparison
-- AST from these structured parts and binds the value(s) as JDBC parameters — admins never
-- author raw SQL, and nothing is string-concatenated.
--
-- applies_to_* polarity is the INVERSE of masking_policy.reveal_to_*: where masking REVEALS
-- to the listed targets, row security APPLIES to them. All three applies_to_* empty ⇒ the
-- policy filters EVERY submitter (governance-safe default); a non-empty list narrows the
-- policy to submitters whose role / group / user id matches. There is NO implicit ADMIN
-- bypass — when applies_to_* are empty, admins are filtered too, exactly as masking masks
-- admins unless reveal_to lists them.

CREATE TYPE row_security_operator AS ENUM (
    'EQUALS', 'NOT_EQUALS', 'LESS_THAN', 'LESS_THAN_OR_EQUAL',
    'GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'IN', 'NOT_IN');

CREATE TYPE row_security_value_type AS ENUM ('VARIABLE', 'LITERAL');

CREATE TABLE row_security_policy (
    id                   UUID                    PRIMARY KEY,
    organization_id      UUID                    NOT NULL REFERENCES organizations(id),
    datasource_id        UUID                    NOT NULL REFERENCES datasources(id),
    table_name           TEXT                    NOT NULL,
    column_name          TEXT                    NOT NULL,
    operator             row_security_operator   NOT NULL,
    value_type           row_security_value_type NOT NULL,
    value_expression     TEXT                    NOT NULL,
    applies_to_roles     TEXT[],
    applies_to_group_ids UUID[],
    applies_to_user_ids  UUID[],
    enabled              BOOLEAN                 NOT NULL DEFAULT true,
    version              BIGINT                  NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ             NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ             NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Backs the per-execution resolution scan (enabled policies for one datasource).
CREATE INDEX idx_row_security_policy_ds_enabled
    ON row_security_policy (organization_id, datasource_id, enabled);
