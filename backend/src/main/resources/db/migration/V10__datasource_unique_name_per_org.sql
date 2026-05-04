ALTER TABLE datasources
    ADD CONSTRAINT uq_datasources_org_name UNIQUE (organization_id, name);
