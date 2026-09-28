-- #944: configurable masking strategies. Six parameterised strategies instead of a user-code SPI:
-- REGEX_REPLACE (pattern + replacement), CONSTANT (replacement), NULLIFY, KEEP_FIRST
-- (visible_prefix), NUMERIC_BUCKET (bucket_size | boundaries) and DATE_GENERALIZE (precision).
-- Parameters ride in the existing strategy_params JSONB on masking_policy and
-- api_connector_masking_policy and are validated at save time; every strategy fails closed to the
-- full mask on a value it cannot apply to.
--
-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block on PostgreSQL. The matching
-- V196__add_configurable_masking_strategies.sql.conf sets executeInTransaction=false so Flyway runs
-- these statements autocommit.
ALTER TYPE masking_strategy ADD VALUE IF NOT EXISTS 'REGEX_REPLACE';
ALTER TYPE masking_strategy ADD VALUE IF NOT EXISTS 'CONSTANT';
ALTER TYPE masking_strategy ADD VALUE IF NOT EXISTS 'NULLIFY';
ALTER TYPE masking_strategy ADD VALUE IF NOT EXISTS 'KEEP_FIRST';
ALTER TYPE masking_strategy ADD VALUE IF NOT EXISTS 'NUMERIC_BUCKET';
ALTER TYPE masking_strategy ADD VALUE IF NOT EXISTS 'DATE_GENERALIZE';
