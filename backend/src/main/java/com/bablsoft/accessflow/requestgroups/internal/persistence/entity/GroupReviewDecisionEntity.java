package com.bablsoft.accessflow.requestgroups.internal.persistence.entity;

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
@Table(name = "group_review_decisions")
@Getter
@Setter
@NoArgsConstructor
public class GroupReviewDecisionEntity {

    @Id
    private UUID id;

    @Column(name = "request_group_id", nullable = false)
    private UUID requestGroupId;

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

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt = Instant.now();
}
