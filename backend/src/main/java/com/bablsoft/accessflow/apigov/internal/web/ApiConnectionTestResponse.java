package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectionTestResult;

public record ApiConnectionTestResponse(boolean success, String message) {

    static ApiConnectionTestResponse from(ApiConnectionTestResult r) {
        return new ApiConnectionTestResponse(r.success(), r.message());
    }
}
