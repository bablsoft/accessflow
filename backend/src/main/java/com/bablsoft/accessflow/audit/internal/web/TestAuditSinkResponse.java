package com.bablsoft.accessflow.audit.internal.web;

record TestAuditSinkResponse(String status, String detail) {

    static TestAuditSinkResponse ok(String detail) {
        return new TestAuditSinkResponse("OK", detail);
    }
}
