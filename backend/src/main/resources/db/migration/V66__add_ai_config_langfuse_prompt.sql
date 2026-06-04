-- AF-333: opt-in Langfuse prompt management per AI configuration. When set (and the org's
-- langfuse_config has prompt_management_enabled = true) the analyzer fetches its system prompt
-- from Langfuse by name + label at render time, falling back to system_prompt_template / the
-- built-in default when Langfuse is unavailable. NULL means "do not use Langfuse for this config",
-- so existing rows need no backfill.
ALTER TABLE ai_config ADD COLUMN langfuse_prompt_name  VARCHAR(255);
ALTER TABLE ai_config ADD COLUMN langfuse_prompt_label VARCHAR(255);
