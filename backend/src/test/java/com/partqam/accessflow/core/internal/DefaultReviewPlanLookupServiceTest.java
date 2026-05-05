package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReviewPlanLookupServiceTest {

    @Mock DatasourceRepository datasourceRepository;
    @Mock ReviewPlanApproverRepository reviewPlanApproverRepository;
    @InjectMocks DefaultReviewPlanLookupService service;

    @Test
    void returnsEmptyWhenDatasourceMissing() {
        var id = UUID.randomUUID();
        when(datasourceRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findForDatasource(id)).isEmpty();
    }

    @Test
    void returnsEmptyWhenDatasourceHasNoReviewPlan() {
        var id = UUID.randomUUID();
        var datasource = new DatasourceEntity();
        datasource.setId(id);
        datasource.setReviewPlan(null);
        when(datasourceRepository.findById(id)).thenReturn(Optional.of(datasource));

        assertThat(service.findForDatasource(id)).isEmpty();
    }

    @Test
    void mapsPlanWithApproverRulesAndComputesMaxStage() {
        var datasourceId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var organization = new OrganizationEntity();
        organization.setId(orgId);
        var plan = new ReviewPlanEntity();
        plan.setId(planId);
        plan.setOrganization(organization);
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(2);
        plan.setAutoApproveReads(true);
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        datasource.setReviewPlan(plan);

        var stage1User = userEntity();
        var approver1 = approverEntity(plan, stage1User, null, 1);
        var approver2 = approverEntity(plan, null, UserRoleType.ADMIN, 2);

        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource));
        when(reviewPlanApproverRepository.findAllByReviewPlan_IdOrderByStageAsc(planId))
                .thenReturn(List.of(approver1, approver2));

        var snapshot = service.findForDatasource(datasourceId).orElseThrow();

        assertThat(snapshot.id()).isEqualTo(planId);
        assertThat(snapshot.organizationId()).isEqualTo(orgId);
        assertThat(snapshot.requiresHumanApproval()).isTrue();
        assertThat(snapshot.minApprovalsRequired()).isEqualTo(2);
        assertThat(snapshot.autoApproveReads()).isTrue();
        assertThat(snapshot.maxStage()).isEqualTo(2);
        assertThat(snapshot.approvers())
                .extracting("userId", "role", "stage")
                .containsExactly(
                        tuple(stage1User.getId(), null, 1),
                        tuple(null, UserRoleType.ADMIN, 2));
    }

    @Test
    void mapsPlanWithNoApproversToZeroMaxStage() {
        var datasourceId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        var plan = new ReviewPlanEntity();
        plan.setId(planId);
        plan.setOrganization(organization);
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        datasource.setReviewPlan(plan);

        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource));
        when(reviewPlanApproverRepository.findAllByReviewPlan_IdOrderByStageAsc(planId))
                .thenReturn(List.of());

        var snapshot = service.findForDatasource(datasourceId).orElseThrow();

        assertThat(snapshot.maxStage()).isEqualTo(0);
        assertThat(snapshot.approvers()).isEmpty();
    }

    private static UserEntity userEntity() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static ReviewPlanApproverEntity approverEntity(ReviewPlanEntity plan, UserEntity user,
                                                           UserRoleType role, int stage) {
        var entity = new ReviewPlanApproverEntity();
        entity.setId(UUID.randomUUID());
        entity.setReviewPlan(plan);
        entity.setUser(user);
        entity.setRole(role);
        entity.setStage(stage);
        return entity;
    }
}
