package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
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

/** What the external decision hook answered for one query (#945) — every consult, failures included. */
@Entity
@Table(name = "decision_hook_results")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DecisionHookResultEntity {

    @Id
    private UUID id;

    @Column(name = "query_request_id", nullable = false)
    private UUID queryRequestId;

    @Column(name = "decision_hook_id", nullable = false)
    private UUID decisionHookId;

    @Column(name = "decision_hook_name", nullable = false, length = 255)
    private String decisionHookName;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "outcome", nullable = false, columnDefinition = "decision_hook_outcome")
    private DecisionHookOutcome outcome;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "failure", columnDefinition = "decision_hook_failure")
    private DecisionHookFailure failure;

    @Column(name = "requested_approvals")
    private Integer requestedApprovals;

    @Column(length = 500)
    private String reason;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
