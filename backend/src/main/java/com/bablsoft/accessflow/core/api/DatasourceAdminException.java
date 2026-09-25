package com.bablsoft.accessflow.core.api;

public sealed class DatasourceAdminException extends RuntimeException
        permits DatasourceNotFoundException,
                DatasourceNameAlreadyExistsException,
                DatasourcePermissionAlreadyExistsException,
                DatasourceGroupPermissionAlreadyExistsException,
                DatasourcePermissionNotFoundException,
                DatasourceConnectionTestException,
                IllegalDatasourcePermissionException,
                DeniedColumnsNotSupportedException,
                DeniedShapesNotSupportedException,
                MissingAiConfigForDatasourceException,
                TableNotFoundException {

    protected DatasourceAdminException(String message) {
        super(message);
    }
}
