package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
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
@Table(name = "api_connector_masking_policy")
@Getter
@Setter
@NoArgsConstructor
public class ApiConnectorMaskingPolicyEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "matcher_type", nullable = false, columnDefinition = "api_masking_matcher_type")
    private ApiMaskingMatcherType matcherType;

    // Required for SCHEMA_FIELD; nullable otherwise (validated in the admin service, not the DB).
    @Column(name = "operation_id", columnDefinition = "text")
    private String operationId;

    @Column(name = "field_ref", nullable = false, columnDefinition = "text")
    private String fieldRef;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "masking_strategy")
    private MaskingStrategy strategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_params", nullable = false, columnDefinition = "jsonb")
    private String strategyParams = "{}";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reveal_to_roles", columnDefinition = "text[]")
    private String[] revealToRoles;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reveal_to_group_ids", columnDefinition = "uuid[]")
    private UUID[] revealToGroupIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reveal_to_user_ids", columnDefinition = "uuid[]")
    private UUID[] revealToUserIds;

    @Column(nullable = false)
    private boolean enabled = true;

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
