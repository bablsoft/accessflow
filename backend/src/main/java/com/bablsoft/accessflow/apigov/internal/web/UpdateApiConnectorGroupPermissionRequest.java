package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorGroupPermissionCommand;

import java.time.Instant;
import java.util.List;

public record UpdateApiConnectorGroupPermissionRequest(
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {

    UpdateApiConnectorGroupPermissionCommand toCommand() {
        return new UpdateApiConnectorGroupPermissionCommand(canRead, canWrite, canBreakGlass,
                expiresAt, allowedOperations, restrictedResponseFields);
    }
}
