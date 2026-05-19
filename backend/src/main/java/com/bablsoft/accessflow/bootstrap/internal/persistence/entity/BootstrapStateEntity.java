package com.bablsoft.accessflow.bootstrap.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bootstrap_state",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_bootstrap_state_key",
                columnNames = {"organization_id", "resource_type", "resource_id"}))
@Getter
@Setter
@NoArgsConstructor
public class BootstrapStateEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "resource_type", nullable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "spec_fingerprint", nullable = false, length = 64)
    private String specFingerprint;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
