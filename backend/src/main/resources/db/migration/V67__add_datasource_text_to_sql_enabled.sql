-- AF-335: per-datasource toggle for natural-language → SQL generation. Opt-in (default false),
-- gated independently of ai_analysis_enabled but sharing the datasource's ai_config_id.
ALTER TABLE datasources ADD COLUMN text_to_sql_enabled BOOLEAN NOT NULL DEFAULT false;
