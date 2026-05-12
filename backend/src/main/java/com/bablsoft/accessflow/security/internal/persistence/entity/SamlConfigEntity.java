package com.bablsoft.accessflow.security.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.bablsoft.accessflow.core.api.UserRoleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saml_config")
@Getter
@Setter
@NoArgsConstructor
public class SamlConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(name = "idp_metadata_url", length = 1024)
    private String idpMetadataUrl;

    @Column(name = "idp_entity_id", length = 1024)
    private String idpEntityId;

    @Column(name = "sp_entity_id", length = 1024)
    private String spEntityId;

    @Column(name = "acs_url", length = 1024)
    private String acsUrl;

    @Column(name = "slo_url", length = 1024)
    private String sloUrl;

    @JsonIgnore
    @Column(name = "signing_cert_pem", columnDefinition = "text")
    private String signingCertPem;

    @Column(name = "attr_email", nullable = false, length = 255)
    private String attrEmail = "email";

    @Column(name = "attr_display_name", nullable = false, length = 255)
    private String attrDisplayName = "displayName";

    @Column(name = "attr_role", length = 255)
    private String attrRole;

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
