package com.bablsoft.accessflow.core.api;

public sealed class DatasourceAdminException extends RuntimeException
        permits DatasourceNotFoundException,
                DatasourceNameAlreadyExistsException,
                DatasourcePermissionAlreadyExistsException,
                DatasourceGroupPermissionAlreadyExistsException,
                DatasourcePermissionNotFoundException,
                DatasourceConnectionTestException,
                IllegalDatasourcePermissionException,
                MissingAiConfigForDatasourceException,
                TableNotFoundException {

    protected DatasourceAdminException(String message) {
        super(message);
    }
}
