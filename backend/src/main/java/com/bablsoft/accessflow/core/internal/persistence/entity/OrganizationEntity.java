package com.bablsoft.accessflow.core.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
public class OrganizationEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    // Per-org quotas (AF-456). NULL or 0 means unlimited; enforced at the service layer.
    @Column(name = "max_datasources")
    private Integer maxDatasources;

    @Column(name = "max_users")
    private Integer maxUsers;

    @Column(name = "max_queries_per_day")
    private Integer maxQueriesPerDay;

    @Column(name = "disabled", nullable = false)
    private boolean disabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
