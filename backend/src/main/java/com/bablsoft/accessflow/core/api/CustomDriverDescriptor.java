package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Cross-module DTO describing a single admin-uploaded JDBC driver. Carries the on-disk
 * storage path and pinned SHA-256 so the proxy module can re-verify before loading the JAR
 * into a child classloader. Created by {@code core/internal} services; consumed by the proxy
 * module to resolve per-datasource classloaders.
 */
public record CustomDriverDescriptor(
        UUID id,
        UUID organizationId,
        DbType targetDbType,
        String vendorName,
        String driverClass,
        String jarFilename,
        String jarSha256,
        long jarSizeBytes,
        String storagePath) {
}
