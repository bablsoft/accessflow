-- AF-150: ai_config becomes many-rows-per-org; datasources bind to a specific ai_config.

ALTER TABLE ai_config ADD COLUMN name VARCHAR(255);

UPDATE ai_config SET name = 'Default ' || provider;

ALTER TABLE ai_config ALTER COLUMN name SET NOT NULL;

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'ai_config'::regclass
       AND contype = 'u'
       AND pg_get_constraintdef(oid) = 'UNIQUE (organization_id)';
    IF constraint_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE ai_config DROP CONSTRAINT ' || quote_ident(constraint_name);
    END IF;
END $$;

CREATE UNIQUE INDEX ai_config_org_name_lower_idx
    ON ai_config (organization_id, lower(name));

ALTER TABLE ai_config
    DROP COLUMN enable_ai_default,
    DROP COLUMN auto_approve_low,
    DROP COLUMN block_critical,
    DROP COLUMN include_schema;

ALTER TABLE datasources
    ADD COLUMN ai_config_id UUID NULL REFERENCES ai_config(id) ON DELETE SET NULL;

UPDATE datasources d
   SET ai_config_id = (
       SELECT a.id
         FROM ai_config a
        WHERE a.organization_id = d.organization_id
        LIMIT 1)
 WHERE d.ai_analysis_enabled = true;

CREATE INDEX datasources_ai_config_id_idx ON datasources(ai_config_id);
