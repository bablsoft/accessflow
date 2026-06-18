package com.bablsoft.accessflow.core.api;

public sealed class OrganizationAdminException extends RuntimeException
        permits OrganizationNotFoundException {

    protected OrganizationAdminException(String message) {
        super(message);
    }
}
