package com.partqam.accessflow.core.api;

import java.util.UUID;

public final class DatasourcePermissionNotFoundException extends DatasourceAdminException {

    public DatasourcePermissionNotFoundException(UUID id) {
        super("Datasource permission not found: " + id);
    }
}
