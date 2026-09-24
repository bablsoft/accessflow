-- Table and schema deny-lists (#939): a denial always beats the allow-list, and denials merge as a
-- union across a user's grants. Entries are 'schema', 'table' or 'schema.table'. Nullable: NULL/empty
-- denies nothing.
ALTER TABLE datasource_user_permissions ADD COLUMN denied_schemas TEXT[];
ALTER TABLE datasource_user_permissions ADD COLUMN denied_tables TEXT[];
ALTER TABLE datasource_group_permissions ADD COLUMN denied_schemas TEXT[];
ALTER TABLE datasource_group_permissions ADD COLUMN denied_tables TEXT[];
