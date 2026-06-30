package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth;
import com.bablsoft.accessflow.apigov.api.Oauth2GrantType;
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trace_header_mapping", nullable = false, columnDefinition = "jsonb")
    private String traceHeaderMapping = "{\"traceparent\":\"traceparent\",\"tracestate\":\"tracestate\"}";

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

    @Column(name = "oauth2_token_uri", columnDefinition = "text")
    private String oauth2TokenUri;

    @Column(name = "oauth2_client_id", columnDefinition = "text")
    private String oauth2ClientId;

    @JsonIgnore
    @Column(name = "oauth2_client_secret_encrypted", columnDefinition = "text")
    private String oauth2ClientSecretEncrypted;

    @Column(name = "oauth2_scopes", columnDefinition = "text")
    private String oauth2Scopes;

    @Column(name = "oauth2_audience", columnDefinition = "text")
    private String oauth2Audience;

    @JsonIgnore
    @Column(name = "oauth2_refresh_token_encrypted", columnDefinition = "text")
    private String oauth2RefreshTokenEncrypted;

    @Column(name = "oauth2_username", columnDefinition = "text")
    private String oauth2Username;

    @JsonIgnore
    @Column(name = "oauth2_password_encrypted", columnDefinition = "text")
    private String oauth2PasswordEncrypted;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "oauth2_grant_type", nullable = false, columnDefinition = "oauth2_grant_type")
    private Oauth2GrantType oauth2GrantType = Oauth2GrantType.CLIENT_CREDENTIALS;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "oauth2_client_auth", nullable = false, columnDefinition = "oauth2_client_auth")
    private Oauth2ClientAuth oauth2ClientAuth = Oauth2ClientAuth.CLIENT_SECRET_BASIC;

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
    private long maxResponseBytes = 10_485_760L;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
