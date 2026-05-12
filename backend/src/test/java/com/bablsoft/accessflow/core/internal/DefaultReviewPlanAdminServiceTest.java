package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateReviewPlanCommand;
import com.bablsoft.accessflow.core.api.IllegalReviewPlanException;
import com.bablsoft.accessflow.core.api.ReviewPlanInUseException;
import com.bablsoft.accessflow.core.api.ReviewPlanNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ReviewPlanNotFoundException;
import com.bablsoft.accessflow.core.api.ReviewPlanView;
import com.bablsoft.accessflow.core.api.UpdateReviewPlanCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReviewPlanAdminServiceTest {

    @Mock ReviewPlanRepository reviewPlanRepository;
    @Mock ReviewPlanApproverRepository approverRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @Mock DatasourceRepository datasourceRepository;
    @InjectMocks DefaultReviewPlanAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID otherOrgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @Test
    void createPersistsPlanAndApproversWhenHumanApprovalRequired() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Default"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(reviewPlanRepository.save(any(ReviewPlanEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(approverRepository.findAllByReviewPlan_IdOrderByStageAsc(any()))
                .thenReturn(List.of());

        var command = new CreateReviewPlanCommand(orgId, "Default", "desc",
                true, true, 1, 24, false, List.of(),
                List.of(new ReviewPlanView.ApproverRule(null, UserRoleType.REVIEWER, 1)));
        var view = service.create(command);

        assertThat(view.name()).isEqualTo("Default");
        assertThat(view.requiresHumanApproval()).isTrue();
        assertThat(view.requiresAiReview()).isTrue();
        assertThat(view.minApprovalsRequired()).isEqualTo(1);
        verify(approverRepository).deleteAllByReviewPlan_Id(any(UUID.class));
        verify(approverRepository).save(any(ReviewPlanApproverEntity.class));
    }

    @Test
    void createSkipsApproverValidationWhenHumanApprovalDisabled() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Auto"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(reviewPlanRepository.save(any(ReviewPlanEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(approverRepository.findAllByReviewPlan_IdOrderByStageAsc(any()))
                .thenReturn(List.of());

        var command = new CreateReviewPlanCommand(orgId, "Auto", null,
                true, false, null, null, true, null, null);
        var view = service.create(command);

        assertThat(view.requiresHumanApproval()).isFalse();
        assertThat(view.autoApproveReads()).isTrue();
        verify(approverRepository, never()).save(any(ReviewPlanApproverEntity.class));
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create(new CreateReviewPlanCommand(orgId, " ",
                null, null, true, null, null, null, null, List.of())))
                .isInstanceOf(IllegalReviewPlanException.class);
        verify(reviewPlanRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateName() {
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Dup"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateReviewPlanCommand(orgId, "Dup",
                null, null, true, null, null, null, null,
                List.of(new ReviewPlanView.ApproverRule(null, UserRoleType.REVIEWER, 1)))))
                .isInstanceOf(ReviewPlanNameAlreadyExistsException.class)
                .hasMessageContaining("Dup");
    }

    @Test
    void createRejectsHumanApprovalWithoutApprovers() {
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "P"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateReviewPlanCommand(orgId, "P",
                null, null, true, null, null, null, null, List.of())))
                .isInstanceOf(IllegalReviewPlanException.class)
                .hasMessageContaining("approver");
    }

    @Test
    void createRejectsApproverWithNeitherUserNorRole() {
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "P"))
                .thenReturn(false);

        var bad = new ReviewPlanView.ApproverRule(null, null, 1);
        assertThatThrownBy(() -> service.create(new CreateReviewPlanCommand(orgId, "P",
                null, null, true, null, null, null, null, List.of(bad))))
                .isInstanceOf(IllegalReviewPlanException.class);
    }

    @Test
    void createRejectsApproverRoleNotAdminOrReviewer() {
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "P"))
                .thenReturn(false);

        var bad = new ReviewPlanView.ApproverRule(null, UserRoleType.ANALYST, 1);
        assertThatThrownBy(() -> service.create(new CreateReviewPlanCommand(orgId, "P",
                null, null, true, null, null, null, null, List.of(bad))))
                .isInstanceOf(IllegalReviewPlanException.class);
    }

    @Test
    void createRejectsApproverFromOtherOrg() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "P"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(reviewPlanRepository.save(any(ReviewPlanEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var otherOrg = new OrganizationEntity();
        otherOrg.setId(otherOrgId);
        var stranger = new UserEntity();
        stranger.setId(userId);
        stranger.setOrganization(otherOrg);
        when(userRepository.findById(userId)).thenReturn(Optional.of(stranger));

        var rule = new ReviewPlanView.ApproverRule(userId, null, 1);
        assertThatThrownBy(() -> service.create(new CreateReviewPlanCommand(orgId, "P",
                null, null, true, null, null, null, null, List.of(rule))))
                .isInstanceOf(IllegalReviewPlanException.class)
                .hasMessageContaining("organization");
    }

    @Test
    void createRejectsMinApprovalsExceedingApproverCount() {
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "P"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateReviewPlanCommand(orgId, "P",
                null, null, true, 5, null, null, null,
                List.of(new ReviewPlanView.ApproverRule(null, UserRoleType.REVIEWER, 1)))))
                .isInstanceOf(IllegalReviewPlanException.class)
                .hasMessageContaining("min_approvals");
    }

    @Test
    void getReturnsViewForOwnOrg() {
        var entity = buildPlan();
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));
        when(approverRepository.findAllByReviewPlan_IdOrderByStageAsc(planId)).thenReturn(List.of());

        var view = service.get(planId, orgId);

        assertThat(view.id()).isEqualTo(planId);
        assertThat(view.name()).isEqualTo("Plan");
    }

    @Test
    void getThrowsIfPlanFromOtherOrg() {
        var entity = buildPlan();
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.get(planId, otherOrgId))
                .isInstanceOf(ReviewPlanNotFoundException.class);
    }

    @Test
    void getThrowsIfPlanMissing() {
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(planId, orgId))
                .isInstanceOf(ReviewPlanNotFoundException.class);
    }

    @Test
    void updateAppliesNonNullFieldsAndReplacesApprovers() {
        var entity = buildPlan();
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCaseAndIdNot(
                eq(orgId), eq("Renamed"), eq(planId))).thenReturn(false);
        when(approverRepository.findAllByReviewPlan_IdOrderByStageAsc(planId)).thenReturn(List.of());

        var command = new UpdateReviewPlanCommand("Renamed", "new-desc", false, null,
                null, 48, true, List.of("ch-1"),
                List.of(new ReviewPlanView.ApproverRule(null, UserRoleType.ADMIN, 1)));
        var view = service.update(planId, orgId, command);

        assertThat(view.name()).isEqualTo("Renamed");
        assertThat(view.description()).isEqualTo("new-desc");
        assertThat(view.requiresAiReview()).isFalse();
        assertThat(view.approvalTimeoutHours()).isEqualTo(48);
        assertThat(view.autoApproveReads()).isTrue();
        verify(approverRepository).deleteAllByReviewPlan_Id(planId);
        verify(approverRepository).save(any(ReviewPlanApproverEntity.class));
    }

    @Test
    void updateRejectsRenameToConflictingName() {
        var entity = buildPlan();
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));
        when(reviewPlanRepository.existsByOrganization_IdAndNameIgnoreCaseAndIdNot(
                eq(orgId), eq("Other"), eq(planId))).thenReturn(true);

        var command = new UpdateReviewPlanCommand("Other", null, null, null,
                null, null, null, null, null);
        assertThatThrownBy(() -> service.update(planId, orgId, command))
                .isInstanceOf(ReviewPlanNameAlreadyExistsException.class)
                .hasMessageContaining("Other");
    }

    @Test
    void updateRejectsHumanApprovalToggleWithoutApprovers() {
        var entity = buildPlan();
        entity.setRequiresHumanApproval(true);
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));
        when(approverRepository.findAllByReviewPlan_Id(planId)).thenReturn(List.of());

        var command = new UpdateReviewPlanCommand(null, null, null, null,
                null, null, null, null, null);
        assertThatThrownBy(() -> service.update(planId, orgId, command))
                .isInstanceOf(IllegalReviewPlanException.class);
    }

    @Test
    void deleteRemovesPlanAndApprovers() {
        var entity = buildPlan();
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));
        when(datasourceRepository.existsByReviewPlan_Id(planId)).thenReturn(false);

        service.delete(planId, orgId);

        verify(approverRepository).deleteAllByReviewPlan_Id(planId);
        verify(reviewPlanRepository).delete(entity);
    }

    @Test
    void deleteRejectsWhenPlanIsAttachedToDatasource() {
        var entity = buildPlan();
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));
        when(datasourceRepository.existsByReviewPlan_Id(planId)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(planId, orgId))
                .isInstanceOf(ReviewPlanInUseException.class);
        verify(reviewPlanRepository, never()).delete(any());
    }

    @Test
    void deleteThrowsIfPlanFromOtherOrg() {
        var entity = buildPlan();
        when(reviewPlanRepository.findById(planId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.delete(planId, otherOrgId))
                .isInstanceOf(ReviewPlanNotFoundException.class);
    }

    @Test
    void listScopesByOrganization() {
        when(reviewPlanRepository.findAllByOrganization_IdOrderByNameAsc(orgId))
                .thenReturn(List.of(buildPlan()));
        when(approverRepository.findAllByReviewPlan_IdOrderByStageAsc(planId)).thenReturn(List.of());

        var plans = service.list(orgId);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).name()).isEqualTo("Plan");
    }

    private ReviewPlanEntity buildPlan() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var plan = new ReviewPlanEntity();
        plan.setId(planId);
        plan.setOrganization(org);
        plan.setName("Plan");
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        return plan;
    }
}
