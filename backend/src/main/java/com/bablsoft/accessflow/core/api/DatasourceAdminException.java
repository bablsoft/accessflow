package com.bablsoft.accessflow.core.api;

public sealed class DatasourceAdminException extends RuntimeException
        permits DatasourceNotFoundException,
                DatasourceNameAlreadyExistsException,
                DatasourcePermissionAlreadyExistsException,
                DatasourcePermissionNotFoundException,
                DatasourceConnectionTestException,
                IllegalDatasourcePermissionException,
                MissingAiConfigForDatasourceException {

    protected DatasourceAdminException(String message) {
        super(message);
    }
}
