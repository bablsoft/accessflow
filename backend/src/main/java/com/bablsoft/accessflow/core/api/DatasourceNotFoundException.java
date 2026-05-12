package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class DatasourceNotFoundException extends DatasourceAdminException {

    public DatasourceNotFoundException(UUID id) {
        super("Datasource not found: " + id);
    }
}
