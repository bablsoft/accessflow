package com.bablsoft.accessflow.security.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "oauth2_config",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "provider"}))
@Getter
@Setter
@NoArgsConstructor
public class OAuth2ConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "provider", nullable = false, columnDefinition = "oauth2_provider_type")
    private OAuth2ProviderType provider;

    @Column(name = "client_id", nullable = false, length = 512)
    private String clientId;

    @JsonIgnore
    @Column(name = "client_secret_encrypted", nullable = false, columnDefinition = "text")
    private String clientSecretEncrypted;

    @Column(name = "scopes_override", length = 1024)
    private String scopesOverride;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "authorization_uri", length = 2048)
    private String authorizationUri;

    @Column(name = "token_uri", length = 2048)
    private String tokenUri;

    @Column(name = "user_info_uri", length = 2048)
    private String userInfoUri;

    @Column(name = "jwk_set_uri", length = 2048)
    private String jwkSetUri;

    @Column(name = "issuer_uri", length = 2048)
    private String issuerUri;

    @Column(name = "base_url", length = 2048)
    private String baseUrl;

    @Column(name = "user_name_attribute", length = 255)
    private String userNameAttribute;

    @Column(name = "email_attribute", length = 255)
    private String emailAttribute;

    @Column(name = "email_verified_attribute", length = 255)
    private String emailVerifiedAttribute;

    @Column(name = "display_name_attribute", length = 255)
    private String displayNameAttribute;

    @Column(name = "groups_attribute", length = 255)
    private String groupsAttribute;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_organizations", columnDefinition = "text[]")
    private String[] allowedOrganizations;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_email_domains", columnDefinition = "text[]")
    private String[] allowedEmailDomains;

    /** IdP group claim value → AccessFlow group id (UUID stored as text in JSON). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "group_mappings", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> groupMappings = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "default_role", nullable = false, columnDefinition = "user_role_type")
    private UserRoleType defaultRole = UserRoleType.ANALYST;

    @Column(nullable = false)
    private boolean active = false;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
