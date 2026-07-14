package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.Permission;

import java.util.Set;
import java.util.UUID;

/**
 * The acting user for a collaboration / comment operation. Mirrors {@code ReviewService.ReviewerContext}
 * but is shared between the comment service and the collaboration-access port.
 */
public record CollaboratorContext(UUID userId, UUID organizationId, String roleName,
                                  Set<Permission> permissions) {
}
