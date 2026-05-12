package com.bablsoft.accessflow.core.api;

public final class DatasourceNameAlreadyExistsException extends DatasourceAdminException {

    public DatasourceNameAlreadyExistsException(String name) {
        super("A datasource with name '" + name + "' already exists in this organization");
    }
}
