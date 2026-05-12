package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.CustomDriverView;
import com.bablsoft.accessflow.core.api.DbType;

import java.time.Instant;
import java.util.UUID;

public record CustomDriverResponse(
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

    public static CustomDriverResponse from(CustomDriverView view) {
        return new CustomDriverResponse(
                view.id(),
                view.organizationId(),
                view.vendorName(),
                view.targetDbType(),
                view.driverClass(),
                view.jarFilename(),
                view.jarSha256(),
                view.jarSizeBytes(),
                view.uploadedByUserId(),
                view.uploadedByDisplayName(),
                view.createdAt());
    }
}
