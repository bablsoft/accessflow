package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class DatasourcePermissionAlreadyExistsException extends DatasourceAdminException {

    public DatasourcePermissionAlreadyExistsException(UUID userId, UUID datasourceId) {
        super("Permission already exists for user " + userId + " on datasource " + datasourceId);
    }
}
