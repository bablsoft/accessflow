CREATE TABLE datasource_user_permissions (
    id                UUID        PRIMARY KEY,
    datasource_id     UUID        NOT NULL REFERENCES datasources(id),
    user_id           UUID        NOT NULL REFERENCES users(id),
    can_read          BOOLEAN     NOT NULL DEFAULT false,
    can_write         BOOLEAN     NOT NULL DEFAULT false,
    can_ddl           BOOLEAN     NOT NULL DEFAULT false,
    row_limit_override INTEGER,
    allowed_schemas   TEXT[],
    allowed_tables    TEXT[],
    expires_at        TIMESTAMPTZ,
    created_by        UUID        NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_permissions_user_datasource UNIQUE (user_id, datasource_id)
);
