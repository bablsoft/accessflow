-- AF-567: extend JIT access requests to API connectors.
-- A request now targets exactly one of a datasource or an API connector. Connector requests
-- reuse can_read/can_write (no DDL, no break-glass, no query pre-approval, no schema/table
-- scope) and carry an optional operation allow-list mirroring
-- api_connector_user_permissions.allowed_operations (NULL = all operations).
--
-- connector_id is a bare UUID (no FK), matching the V100 convention: api_connectors rows are
-- hard-deleted (unlike soft-deleted datasources), and the access-request history must survive a
-- connector's deletion rather than block it.

ALTER TABLE access_grant_request
    ALTER COLUMN datasource_id DROP NOT NULL;

ALTER TABLE access_grant_request
    ADD COLUMN connector_id UUID,
    ADD COLUMN allowed_operations TEXT[];

ALTER TABLE access_grant_request
    ADD CONSTRAINT chk_access_grant_request_resource CHECK (
        (datasource_id IS NOT NULL AND connector_id IS NULL
             AND allowed_operations IS NULL)
        OR
        (connector_id IS NOT NULL AND datasource_id IS NULL
             AND NOT can_ddl AND NOT pre_approve_queries
             AND allowed_schemas IS NULL AND allowed_tables IS NULL)
    );

CREATE INDEX idx_access_grant_request_connector ON access_grant_request (connector_id)
    WHERE connector_id IS NOT NULL;
