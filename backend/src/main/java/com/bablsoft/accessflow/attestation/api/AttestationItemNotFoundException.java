package com.bablsoft.accessflow.attestation.api;

import java.util.UUID;

public class AttestationItemNotFoundException extends AttestationException {

    public AttestationItemNotFoundException(UUID itemId) {
        super("Attestation item not found: " + itemId);
    }
}
