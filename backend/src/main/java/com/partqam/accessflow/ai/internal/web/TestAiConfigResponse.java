package com.partqam.accessflow.ai.internal.web;

record TestAiConfigResponse(String status, String detail) {

    static TestAiConfigResponse ok(String detail) {
        return new TestAiConfigResponse("OK", detail);
    }

    static TestAiConfigResponse error(String detail) {
        return new TestAiConfigResponse("ERROR", detail);
    }
}
