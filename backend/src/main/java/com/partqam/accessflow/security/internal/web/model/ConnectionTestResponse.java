package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.ConnectionTestResult;

public record ConnectionTestResponse(boolean ok, long latencyMs, String message) {

    public static ConnectionTestResponse from(ConnectionTestResult result) {
        return new ConnectionTestResponse(result.ok(), result.latencyMs(), result.message());
    }
}
