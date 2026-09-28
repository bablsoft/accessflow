package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** An external policy decision hook (#945): an organization default or one datasource's hook. */
@Entity
@Table(name = "decision_hooks")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DecisionHookEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** {@code null} = the organization default. */
    @Column(name = "datasource_id")
    private UUID datasourceId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "endpoint_url", nullable = false, length = 2048)
    private String endpointUrl;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @JsonIgnore
    @Column(name = "secret_encrypted", nullable = false, columnDefinition = "text")
    private String secretEncrypted;

    @Column(name = "include_sql", nullable = false)
    private boolean includeSql;

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
