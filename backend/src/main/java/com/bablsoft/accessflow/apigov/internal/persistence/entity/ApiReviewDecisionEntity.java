package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DecisionType;
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

@Entity
@Table(name = "api_review_decisions")
@Getter
@Setter
@NoArgsConstructor
public class ApiReviewDecisionEntity {

    @Id
    private UUID id;

    @Column(name = "api_request_id", nullable = false)
    private UUID apiRequestId;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "decision")
    private DecisionType decision;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(nullable = false)
    private int stage = 1;

    /**
     * The delegator whose authority the reviewer borrowed under an out-of-office delegation
     * (#622), or null when they were eligible in their own right. {@code reviewerId} always stays
     * the acting human, which is what keeps one human to one vote under the unique index.
     */
    @Column(name = "on_behalf_of_user_id")
    private UUID onBehalfOfUserId;

    /** The delegation that authorised this decision, pinned so a later revoke cannot erase it. */
    @Column(name = "delegation_id")
    private UUID delegationId;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt = Instant.now();
}
