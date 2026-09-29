-- #1085: row_limit_override must be NULL (use the datasource cap) or >= 1. Until now only the web
-- DTO enforced it; since #933 a non-positive value fails every execution for that grantee. Normalise
-- any existing bad rows to NULL first so the constraint can be added on a dirty table.
UPDATE datasource_user_permissions SET row_limit_override = NULL WHERE row_limit_override < 1;
UPDATE datasource_group_permissions SET row_limit_override = NULL WHERE row_limit_override < 1;

ALTER TABLE datasource_user_permissions ADD CONSTRAINT chk_dup_row_limit_override_positive
    CHECK (row_limit_override IS NULL OR row_limit_override >= 1);
ALTER TABLE datasource_group_permissions ADD CONSTRAINT chk_dgp_row_limit_override_positive
    CHECK (row_limit_override IS NULL OR row_limit_override >= 1);
