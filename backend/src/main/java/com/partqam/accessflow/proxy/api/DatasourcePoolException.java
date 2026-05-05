package com.partqam.accessflow.proxy.api;

public sealed class DatasourcePoolException extends RuntimeException
        permits DatasourceUnavailableException, PoolInitializationException {

    protected DatasourcePoolException(String message) {
        super(message);
    }

    protected DatasourcePoolException(String message, Throwable cause) {
        super(message, cause);
    }
}
