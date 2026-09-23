package com.bablsoft.accessflow.scheduling.internal.persistence.entity;

import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/** One recorded run of a {@code @Scheduled} job (#923). Platform-scoped; references nothing. */
@Entity
@Table(name = "job_executions")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class JobExecutionEntity {

    @Id
    private UUID id;

    @Column(name = "job_name", nullable = false, columnDefinition = "TEXT")
    private String jobName;

    @Column(name = "lock_name", columnDefinition = "TEXT")
    private String lockName;

    @Column(name = "instance_id", columnDefinition = "TEXT")
    private String instanceId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", nullable = false, columnDefinition = "job_execution_status")
    private JobExecutionStatus status;

    @Column(name = "error_class", columnDefinition = "TEXT")
    private String errorClass;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
