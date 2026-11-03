package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.Permission;

import java.util.Set;
import java.util.UUID;

/** {@code againstApiRequestId} null means "the previous run" (#887). */
public record ApiResponseDiffCommand(UUID apiRequestId, UUID againstApiRequestId, UUID organizationId,
                                     UUID userId, Set<Permission> callerPermissions) {
}
