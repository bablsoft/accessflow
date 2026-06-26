package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "api_connectors")
@Getter
@Setter
@NoArgsConstructor
public class ApiConnectorEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "api_protocol")
    private ApiProtocol protocol;

    @Column(name = "base_url", nullable = false, columnDefinition = "text")
    private String baseUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_headers", nullable = false, columnDefinition = "jsonb")
    private String defaultHeaders = "{}";

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 30000;

    @Column(name = "tls_verify", nullable = false)
    private boolean tlsVerify = true;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "auth_method", nullable = false, columnDefinition = "api_auth_method")
    private ApiAuthMethod authMethod = ApiAuthMethod.NONE;

    @JsonIgnore
    @Column(name = "auth_credentials_encrypted", columnDefinition = "text")
    private String authCredentialsEncrypted;

    @Column(name = "review_plan_id")
    private UUID reviewPlanId;

    @Column(name = "ai_analysis_enabled", nullable = false)
    private boolean aiAnalysisEnabled = true;

    @Column(name = "ai_config_id")
    private UUID aiConfigId;

    @Column(name = "text_to_api_enabled", nullable = false)
    private boolean textToApiEnabled = false;

    @Column(name = "require_review_reads", nullable = false)
    private boolean requireReviewReads = false;

    @Column(name = "require_review_writes", nullable = false)
    private boolean requireReviewWrites = true;

    @Column(name = "max_response_bytes", nullable = false)
    private long maxResponseBytes = 1048576L;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
