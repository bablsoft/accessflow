package com.bablsoft.accessflow.scim.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.UserRoleType;
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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scim_config")
@Getter
@Setter
@NoArgsConstructor
public class ScimConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "attr_email", nullable = false, length = 255)
    private String attrEmail = "userName";

    @Column(name = "attr_display_name", nullable = false, length = 255)
    private String attrDisplayName = "displayName";

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "default_role", nullable = false, columnDefinition = "user_role_type")
    private UserRoleType defaultRole = UserRoleType.ANALYST;

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
