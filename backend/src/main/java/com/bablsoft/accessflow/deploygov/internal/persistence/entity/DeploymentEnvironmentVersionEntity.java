package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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

/**
 * The current/previous deployed-version projection for one environment (#741) — a read model
 * maintained by the version tracker inside the EXECUTED/outcome transactions, never an input to
 * gate, approval or routing decisions. Deployment history stays fully derived from
 * {@code deployment_requests}; this row only answers "what is deployed here right now".
 *
 * <p>After a {@code FAILED}/{@code ROLLED_BACK} revert, {@code lastOutcome} records the outcome
 * of the <em>reverted</em> request while {@code current*} points at the older deploy it reverted
 * to — so the UI can badge "failed / rolled back, reverted to X". A second consecutive rollback
 * leaves {@code current*} null: unknown, see history. The optimistic-lock column is
 * {@code version_lock} because {@code version} is domain vocabulary here.
 */
@Entity
@Table(name = "deployment_environment_versions")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentEnvironmentVersionEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(name = "environment_id", nullable = false, unique = true)
    private UUID environmentId;

    @Column(name = "current_version", length = 255)
    private String currentVersion;

    @Column(name = "current_request_id")
    private UUID currentRequestId;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    @Column(name = "previous_version", length = 255)
    private String previousVersion;

    @Column(name = "previous_request_id")
    private UUID previousRequestId;

    @Column(name = "previous_deployed_at")
    private Instant previousDeployedAt;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "last_outcome", columnDefinition = "deployment_outcome")
    private DeploymentOutcome lastOutcome;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
