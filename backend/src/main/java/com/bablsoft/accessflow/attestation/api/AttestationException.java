package com.bablsoft.accessflow.attestation.api;

/** Base type for attestation-module domain exceptions. */
public abstract class AttestationException extends RuntimeException {

    protected AttestationException(String message) {
        super(message);
    }
}
