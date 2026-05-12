package com.bablsoft.accessflow.notifications.internal.web;

record TestNotificationResponse(String status, String detail) {

    static TestNotificationResponse ok(String detail) {
        return new TestNotificationResponse("OK", detail);
    }
}
