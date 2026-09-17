package com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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

/**
 * The 1:1 detail row of a service-account user (#868). The PK is the {@code users.id} it extends;
 * {@code user_id}, {@code organization_id} and {@code owner_user_id} are real FKs in the schema but
 * mapped as bare UUIDs here — the module owns no JPA association into {@code core}.
 */
@Entity
@Table(name = "service_accounts")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ServiceAccountEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "managed_by", nullable = false, columnDefinition = "service_account_source")
    private ServiceAccountSource managedBy;

    // NULL = every tool, empty = none (#872).
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "mcp_tool_allow_list", columnDefinition = "text[]")
    private String[] mcpToolAllowList;

    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @Column(name = "rate_limit_per_day")
    private Integer rateLimitPerDay;

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
}
