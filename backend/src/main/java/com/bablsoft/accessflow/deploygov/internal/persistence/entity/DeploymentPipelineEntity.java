package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_pipelines")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentPipelineEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "pipeline_provider")
    private PipelineProvider provider;

    @Column(name = "repository_url", columnDefinition = "text")
    private String repositoryUrl;

    @Column(name = "project_ref", columnDefinition = "text")
    private String projectRef;

    @Column(name = "review_plan_id")
    private UUID reviewPlanId;

    @Column(name = "ai_analysis_enabled", nullable = false)
    private boolean aiAnalysisEnabled = true;

    @Column(name = "ai_config_id")
    private UUID aiConfigId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
