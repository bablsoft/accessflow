package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.LangfuseConnectionTestResult;

record LangfuseConfigTestResponse(String status, String message) {

    static LangfuseConfigTestResponse from(LangfuseConnectionTestResult result) {
        return new LangfuseConfigTestResponse(result.success() ? "OK" : "ERROR", result.message());
    }
}
