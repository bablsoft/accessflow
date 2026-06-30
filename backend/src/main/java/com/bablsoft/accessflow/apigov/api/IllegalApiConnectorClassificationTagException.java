package com.bablsoft.accessflow.apigov.api;

/**
 * A connector classification tag failed validation (missing operation for SCHEMA_FIELD, blank field
 * reference, no classifications). The message is resolved at the throw site via {@code MessageSource}
 * and surfaced verbatim as the 422 detail.
 */
public class IllegalApiConnectorClassificationTagException extends ApiGovException {

    public IllegalApiConnectorClassificationTagException(String message) {
        super(message);
    }
}
