package com.bablsoft.accessflow.core.api;

public sealed class RowLimitPolicyException extends RuntimeException
        permits RowLimitPolicyNotFoundException, IllegalRowLimitPolicyException {

    protected RowLimitPolicyException(String message) {
        super(message);
    }
}
