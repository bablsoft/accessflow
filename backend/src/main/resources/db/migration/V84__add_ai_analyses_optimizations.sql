-- AF-451: store the AI analyzer's structured optimization suggestions (dialect-aware index DDL +
-- query rewrites) alongside the existing issues. Defaulted + JSONB so existing rows and zero-downtime
-- deploys are unaffected.
ALTER TABLE ai_analyses
    ADD COLUMN optimizations JSONB NOT NULL DEFAULT '[]';
