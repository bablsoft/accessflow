package com.bablsoft.accessflow.core.events;

import com.bablsoft.accessflow.core.api.DbType;

import java.util.UUID;

/**
 * Published when an admin successfully uploads a new JDBC driver JAR. Consumed by the audit
 * module to record the action.
 */
public record CustomJdbcDriverRegisteredEvent(
        UUID driverId,
        UUID organizationId,
        UUID uploadedByUserId,
        String vendorName,
        DbType targetDbType,
        String jarSha256) {
}
