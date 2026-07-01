package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class DatasourceGroupPermissionAlreadyExistsException extends DatasourceAdminException {

    public DatasourceGroupPermissionAlreadyExistsException(UUID groupId, UUID datasourceId) {
        super("Permission already exists for group " + groupId + " on datasource " + datasourceId);
    }
}
