package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiOperation;

public record ApiOperationResponse(
        String operationId, String verb, String path, String summary, boolean write) {

    static ApiOperationResponse from(ApiOperation o) {
        return new ApiOperationResponse(o.operationId(), o.verb(), o.path(), o.summary(), o.write());
    }
}
