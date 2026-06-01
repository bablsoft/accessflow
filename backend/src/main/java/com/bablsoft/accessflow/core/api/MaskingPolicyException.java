package com.bablsoft.accessflow.core.api;

public sealed class MaskingPolicyException extends RuntimeException
        permits MaskingPolicyNotFoundException, IllegalMaskingPolicyException {

    protected MaskingPolicyException(String message) {
        super(message);
    }
}
