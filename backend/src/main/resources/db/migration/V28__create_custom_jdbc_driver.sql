CREATE TABLE custom_jdbc_driver (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations(id),
    vendor_name     VARCHAR(100) NOT NULL,
    target_db_type  db_type      NOT NULL,
    driver_class    VARCHAR(255) NOT NULL,
    jar_filename    VARCHAR(255) NOT NULL,
    jar_sha256      VARCHAR(64)  NOT NULL,
    jar_size_bytes  BIGINT       NOT NULL,
    storage_path    TEXT         NOT NULL,
    uploaded_by     UUID         NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT custom_jdbc_driver_org_sha_unique UNIQUE (organization_id, jar_sha256)
);

CREATE INDEX idx_custom_jdbc_driver_org_dbtype
    ON custom_jdbc_driver(organization_id, target_db_type);

-- Per-datasource custom driver selection. ON DELETE RESTRICT enforces that a driver
-- referenced by any datasource cannot be deleted; the service layer translates the
-- violation into a 409 Conflict listing the referencing datasource ids.
ALTER TABLE datasources
    ADD COLUMN custom_driver_id UUID REFERENCES custom_jdbc_driver(id) ON DELETE RESTRICT;

ALTER TABLE datasources
    ADD COLUMN jdbc_url_override TEXT;

-- Datasources with db_type = CUSTOM use jdbc_url_override and have no host/port/database_name.
ALTER TABLE datasources ALTER COLUMN host DROP NOT NULL;
ALTER TABLE datasources ALTER COLUMN port DROP NOT NULL;
ALTER TABLE datasources ALTER COLUMN database_name DROP NOT NULL;

CREATE INDEX idx_datasources_custom_driver
    ON datasources(custom_driver_id) WHERE custom_driver_id IS NOT NULL;
