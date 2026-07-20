package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorGroupPermissionCommand;

import java.time.Instant;
import java.util.List;

public record UpdateApiConnectorGroupPermissionRequest(
        boolean canRead,
        boolean canWrite,
        boolean canBreakGlass,
        /** AF-613. Boxed so an existing client that omits it still works; null means false. */
        Boolean canOverrideVariables,
        Instant expiresAt,
        List<String> allowedOperations,
        List<String> restrictedResponseFields) {

    UpdateApiConnectorGroupPermissionCommand toCommand() {
        return new UpdateApiConnectorGroupPermissionCommand(canRead, canWrite, canBreakGlass,
                Boolean.TRUE.equals(canOverrideVariables),
                expiresAt, allowedOperations, restrictedResponseFields);
    }
}
