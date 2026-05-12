package com.bablsoft.accessflow.core.events;

import com.bablsoft.accessflow.core.api.DbType;

import java.util.UUID;

/**
 * Published when an admin deletes an uploaded JDBC driver. Consumed by the audit module to
 * record the action; the proxy module's catalog cache is evicted synchronously in the service
 * before publishing.
 */
public record CustomJdbcDriverDeletedEvent(
        UUID driverId,
        UUID organizationId,
        UUID deletedByUserId,
        String vendorName,
        DbType targetDbType,
        String jarSha256) {
}
