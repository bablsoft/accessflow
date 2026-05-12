package com.bablsoft.accessflow.core.api;

import java.io.InputStream;
import java.util.UUID;

/**
 * Service-layer command for registering a new uploaded JDBC driver. The {@code content} stream
 * is consumed once during persistence; callers (the REST controller) must not reuse it.
 */
public record UploadCustomDriverCommand(
        UUID organizationId,
        UUID uploadedByUserId,
        String vendorName,
        DbType targetDbType,
        String driverClass,
        String jarFilename,
        String expectedSha256,
        long contentLength,
        InputStream content
) {}
