package com.bablsoft.accessflow.notifications.internal.web;

/** Outcome of the admin "send a test Slack message" action. */
public record TestSlackResponse(String status, String detail) {

    public static TestSlackResponse ok(String detail) {
        return new TestSlackResponse("OK", detail);
    }

    public static TestSlackResponse error(String detail) {
        return new TestSlackResponse("ERROR", detail);
    }
}
