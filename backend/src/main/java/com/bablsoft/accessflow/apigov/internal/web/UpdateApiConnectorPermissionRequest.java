package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorPermissionCommand;

import java.time.Instant;
import java.util.List;

public record UpdateApiConnectorPermissionRequest(
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        /** AF-613. Boxed so an existing client that omits it still works; null means false. */
        Boolean canOverrideVariables,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {

    UpdateApiConnectorPermissionCommand toCommand() {
        return new UpdateApiConnectorPermissionCommand(canRead, canWrite, canBreakGlass,
                Boolean.TRUE.equals(canOverrideVariables),
                expiresAt, allowedOperations, restrictedResponseFields);
    }
}
