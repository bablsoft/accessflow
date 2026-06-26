package com.bablsoft.accessflow.attestation.api;

/** Thrown when a campaign's scope and datasource selection are inconsistent. */
public class IllegalAttestationScopeException extends AttestationException {

    public IllegalAttestationScopeException(String message) {
        super(message);
    }
}
