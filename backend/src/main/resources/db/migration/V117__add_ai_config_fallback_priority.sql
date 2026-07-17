-- AF-458: AI provider fallback pool.
-- NULL = the config is not a fallback; a non-negative value marks it as an org-wide fallback,
-- tried in ascending priority order when the config bound to a request fails at call time.
ALTER TABLE ai_config
    ADD COLUMN fallback_priority INTEGER CHECK (fallback_priority >= 0);

CREATE INDEX idx_ai_config_org_fallback
    ON ai_config (organization_id, fallback_priority)
    WHERE fallback_priority IS NOT NULL;
