package com.partqam.accessflow.core.internal.persistence.entity;

import com.partqam.accessflow.core.api.UserRoleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "review_plan_approvers")
@Getter
@Setter
@NoArgsConstructor
public class ReviewPlanApproverEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_plan_id", nullable = false)
    private ReviewPlanEntity reviewPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "user_role_type")
    private UserRoleType role;

    @Column(nullable = false)
    private int stage;
}
