package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_connector_user_permissions")
@Getter
@Setter
@NoArgsConstructor
public class ApiConnectorUserPermissionEntity {

    @Id
    private UUID id;

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "can_read", nullable = false)
    private boolean canRead = false;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite = false;

    @Column(name = "can_break_glass", nullable = false)
    private boolean canBreakGlass = false;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_operations", columnDefinition = "text[]")
    private String[] allowedOperations;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "restricted_response_fields", columnDefinition = "text[]")
    private String[] restrictedResponseFields;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
