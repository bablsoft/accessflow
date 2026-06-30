package com.bablsoft.accessflow.apigov.api;

/**
 * A connector masking policy failed validation (missing operation for SCHEMA_FIELD, blank field
 * reference, invalid strategy params, unknown reveal role, reveal target not in org). The message is
 * resolved at the throw site via {@code MessageSource} and surfaced verbatim as the 422 detail.
 */
public class IllegalApiConnectorMaskingPolicyException extends ApiGovException {

    public IllegalApiConnectorMaskingPolicyException(String message) {
        super(message);
    }
}
