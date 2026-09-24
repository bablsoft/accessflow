-- Column-level authorization (#935): a query that references a denied column is rejected before
-- execution. Entries are 'table.column' or 'schema.table.column'. Nullable: NULL/empty denies none.
ALTER TABLE datasource_user_permissions ADD COLUMN denied_columns TEXT[];
ALTER TABLE datasource_group_permissions ADD COLUMN denied_columns TEXT[];
