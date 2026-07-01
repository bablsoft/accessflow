-- AF-530: group-based access grants for datasources & API connectors.
-- Parallel tables to datasource_user_permissions / api_connector_user_permissions, keyed on
-- group_id instead of user_id, so members of a group inherit the grant. Effective access is the
-- most-permissive union of a user's direct grant and every group grant they belong to (resolved in
-- DefaultDatasourceUserPermissionLookupService / EffectiveApiConnectorPermissionResolver).

CREATE TABLE datasource_group_permissions (
    id                 UUID        PRIMARY KEY,
    organization_id    UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    datasource_id      UUID        NOT NULL REFERENCES datasources(id) ON DELETE CASCADE,
    group_id           UUID        NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    can_read           BOOLEAN     NOT NULL DEFAULT FALSE,
    can_write          BOOLEAN     NOT NULL DEFAULT FALSE,
    can_ddl            BOOLEAN     NOT NULL DEFAULT FALSE,
    can_break_glass    BOOLEAN     NOT NULL DEFAULT FALSE,
    row_limit_override INTEGER,
    allowed_schemas    TEXT[],
    allowed_tables     TEXT[],
    restricted_columns TEXT[],
    expires_at         TIMESTAMPTZ,
    created_by         UUID        NOT NULL REFERENCES users(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_datasource_group_perms UNIQUE (datasource_id, group_id)
);

CREATE INDEX idx_datasource_group_perms_datasource ON datasource_group_permissions (datasource_id);

CREATE TABLE api_connector_group_permissions (
    id                         UUID        PRIMARY KEY,
    organization_id            UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    connector_id               UUID        NOT NULL REFERENCES api_connectors(id) ON DELETE CASCADE,
    group_id                   UUID        NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    can_read                   BOOLEAN     NOT NULL DEFAULT FALSE,
    can_write                  BOOLEAN     NOT NULL DEFAULT FALSE,
    can_break_glass            BOOLEAN     NOT NULL DEFAULT FALSE,
    allowed_operations         TEXT[],
    restricted_response_fields TEXT[],
    expires_at                 TIMESTAMPTZ,
    created_by                 UUID        NOT NULL REFERENCES users(id),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_api_connector_group_perms UNIQUE (connector_id, group_id)
);

CREATE INDEX idx_api_connector_group_perms_connector ON api_connector_group_permissions (connector_id);
