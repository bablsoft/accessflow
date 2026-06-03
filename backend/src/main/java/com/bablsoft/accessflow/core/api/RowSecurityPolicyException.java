package com.bablsoft.accessflow.core.api;

public sealed class RowSecurityPolicyException extends RuntimeException
        permits RowSecurityPolicyNotFoundException, IllegalRowSecurityPolicyException {

    protected RowSecurityPolicyException(String message) {
        super(message);
    }
}
