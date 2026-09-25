-- Query-shape deny-lists (#940): a grant may refuse queries by structure — JOIN, UNION, SUBQUERY,
-- CTE, GROUP_BY, HAVING, AGGREGATE, WINDOW_FUNCTION (the QueryShape enum names). Denials merge as a
-- union across a user's grants. Nullable: NULL/empty denies nothing.
ALTER TABLE datasource_user_permissions ADD COLUMN denied_shapes TEXT[];
ALTER TABLE datasource_group_permissions ADD COLUMN denied_shapes TEXT[];
