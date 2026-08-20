package com.bablsoft.accessflow.audit.api;

import java.util.Map;
import java.util.UUID;

/**
 * Command for creating a new audit sink. {@code config} carries the raw input including
 * unencrypted sensitive fields ({@code token}, {@code secret}, {@code secret_access_key});
 * the service encrypts and renames them before persistence.
 */
public record CreateAuditSinkCommand(
        UUID organizationId,
        AuditSinkType type,
        String name,
        Map<String, Object> config) {
}
