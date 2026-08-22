package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A deployment freeze window: pipeline/environment-scoped (both null = org-wide), either a one-off
 * {@code startsAt}/{@code endsAt} interval or a recurring weekly window ({@code daysOfWeek} in
 * ISO-8601 numbering + wall-clock {@code startTime}/{@code endTime} in {@code timezone}) — the DDL
 * CHECK enforces exactly one shape.
 */
@Entity
@Table(name = "deployment_freeze_windows")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentFreezeWindowEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "pipeline_id")
    private UUID pipelineId;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "days_of_week", columnDefinition = "smallint[]")
    private short[] daysOfWeek;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "freeze_behavior")
    private FreezeBehavior behavior;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
