package com.bablsoft.accessflow.access.internal.persistence.entity;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_grant_request")
@Getter
@Setter
@NoArgsConstructor
public class AccessGrantRequestEntity {

    @Id
    private UUID id;

    // Bare UUID references to other modules' aggregates — no JPA relationship across the
    // access ↔ core module boundary (CLAUDE.md: internal entities are module-private).
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    // Exactly one of datasourceId / connectorId is set (DB CHECK chk_access_grant_request_resource).
    @Column(name = "datasource_id")
    private UUID datasourceId;

    @Column(name = "connector_id")
    private UUID connectorId;

    @Column(name = "can_read", nullable = false)
    private boolean canRead = false;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite = false;

    @Column(name = "can_ddl", nullable = false)
    private boolean canDdl = false;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_schemas", columnDefinition = "text[]")
    private String[] allowedSchemas;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_tables", columnDefinition = "text[]")
    private String[] allowedTables;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_operations", columnDefinition = "text[]")
    private String[] allowedOperations;

    @Column(name = "pre_approve_queries", nullable = false)
    private boolean preApproveQueries = false;

    @Column(name = "requested_duration", nullable = false, columnDefinition = "text")
    private String requestedDuration;

    @Column(columnDefinition = "text")
    private String justification;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "access_grant_status")
    private AccessGrantStatus status = AccessGrantStatus.PENDING;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "granted_permission_id")
    private UUID grantedPermissionId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isConnectorRequest() {
        return connectorId != null;
    }
}
