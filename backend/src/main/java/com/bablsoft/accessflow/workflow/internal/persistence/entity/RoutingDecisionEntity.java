package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.workflow.api.RoutingAction;
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

/**
 * The routing decision recorded for one query when a {@code routing_policy} matched. Holds the
 * resolved effect ({@code effective_min_approvals} is the absolute approval count to enforce for
 * ESCALATE / REQUIRE_APPROVALS). Read by the review service to honour the override and by the query
 * detail read path to surface the matched policy.
 */
@Entity
@Table(name = "routing_decision")
@Getter
@Setter
@NoArgsConstructor
public class RoutingDecisionEntity {

    @Id
    private UUID id;

    @Column(name = "query_request_id", nullable = false)
    private UUID queryRequestId;

    @Column(name = "matched_policy_id")
    private UUID matchedPolicyId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "action", nullable = false, columnDefinition = "routing_action")
    private RoutingAction action;

    @Column(name = "effective_min_approvals")
    private Integer effectiveMinApprovals;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
