-- AF-332: per-ai_config editable analyzer system prompt. Admins may override the built-in prompt
-- template on a specific AI configuration; the four {{db_type}} / {{schema_context}} / {{sql}} /
-- {{language}} tokens are substituted at render time. NULL (or blank) means "use the built-in
-- default", so existing rows need no backfill.

ALTER TABLE ai_config ADD COLUMN system_prompt_template TEXT;
