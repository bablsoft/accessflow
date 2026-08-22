package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_pipeline_group_permissions")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentPipelineGroupPermissionEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "can_trigger", nullable = false)
    private boolean canTrigger = false;

    @Column(name = "can_break_glass", nullable = false)
    private boolean canBreakGlass = false;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;
}
