package com.partqam.accessflow.core.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "datasource_user_permissions")
@Getter
@Setter
@NoArgsConstructor
public class DatasourceUserPermissionEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "datasource_id", nullable = false)
    private DatasourceEntity datasource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "can_read", nullable = false)
    private boolean canRead = false;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite = false;

    @Column(name = "can_ddl", nullable = false)
    private boolean canDdl = false;

    @Column(name = "row_limit_override")
    private Integer rowLimitOverride;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_schemas", columnDefinition = "text[]")
    private String[] allowedSchemas;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_tables", columnDefinition = "text[]")
    private String[] allowedTables;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private UserEntity createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
