package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of an admin-uploaded JDBC driver for use in REST responses and audit
 * payloads. Strips the on-disk storage path so it never leaks to clients.
 */
public record CustomDriverView(
        UUID id,
        UUID organizationId,
        String vendorName,
        DbType targetDbType,
        String driverClass,
        String jarFilename,
        String jarSha256,
        long jarSizeBytes,
        UUID uploadedByUserId,
        String uploadedByDisplayName,
        Instant createdAt) {
}
