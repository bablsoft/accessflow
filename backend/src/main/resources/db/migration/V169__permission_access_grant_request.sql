-- #969: record the originating JIT request on the permission row it materialised, so the
-- effective-access report (#859) labels JIT_GRANT from a foreign key instead of correlating on
-- (requester, datasource, active APPROVED grant). Nullable: an admin-created row genuinely has no
-- originating request. ON DELETE SET NULL because the permission row's lifecycle belongs to the
-- core module and must survive a hard-deleted access_grant_request row.
ALTER TABLE datasource_user_permissions
    ADD COLUMN access_grant_request_id UUID REFERENCES access_grant_request(id) ON DELETE SET NULL;

-- Partial, matching the V113 convention: the column is null on every admin-created row.
CREATE INDEX idx_datasource_user_permissions_access_grant_request
    ON datasource_user_permissions (access_grant_request_id)
    WHERE access_grant_request_id IS NOT NULL;

-- Backfill from the exact forward link the materializer already writes (granted_permission_id),
-- never from the (requester, datasource) correlation. Requests whose permission was since replaced
-- or revoked point at a deleted row and simply match nothing. Connector requests are excluded:
-- their granted_permission_id is an api_connector_user_permissions id.
UPDATE datasource_user_permissions p
SET access_grant_request_id = r.id
FROM access_grant_request r
WHERE r.granted_permission_id = p.id
  AND r.datasource_id IS NOT NULL;
