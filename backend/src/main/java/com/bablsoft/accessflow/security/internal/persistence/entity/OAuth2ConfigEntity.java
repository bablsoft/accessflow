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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
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
