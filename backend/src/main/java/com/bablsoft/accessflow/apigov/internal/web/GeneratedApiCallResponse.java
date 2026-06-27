package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAssistService;

public record GeneratedApiCallResponse(String draft) {

    static GeneratedApiCallResponse from(ApiAssistService.GeneratedApiCallView v) {
        return new GeneratedApiCallResponse(v.draft());
    }
}
