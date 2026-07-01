package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

public class ApiConnectorMaskingPolicyNotFoundException extends ApiGovException {

    public ApiConnectorMaskingPolicyNotFoundException(UUID policyId) {
        super("API connector masking policy not found: " + policyId);
    }
}
