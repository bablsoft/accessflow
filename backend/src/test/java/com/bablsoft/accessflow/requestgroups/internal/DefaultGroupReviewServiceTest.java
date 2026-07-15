package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService.ReviewerContext;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.SelfApprovalNotAllowedException;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.GroupReviewDecisionEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.GroupReviewDecisionRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupItemRepository;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultGroupReviewServiceTest {

    @Mock
    private RequestGroupRepository groupRepository;
    @Mock
    private RequestGroupItemRepository itemRepository;
    @Mock
    private GroupReviewDecisionRepository decisionRepository;
    @Mock
    private RequestGroupStateService stateService;
    @Mock
    private GroupReviewPlanResolver reviewPlanResolver;
    @Mock
    private UserQueryService userQueryService;
    @Mock
    private AuditLogService auditLogService;
    @InjectMocks
    private DefaultGroupReviewService service;

    private RequestGroupEntity group;
    private final UUID orgId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(orgId);
        group.setSubmittedBy(submitterId);
        group.setStatus(RequestGroupStatus.PENDING_REVIEW);
        group.setRequiredApprovals(1);
        group.setCurrentReviewStage(1);
    }

    private ReviewerContext adminContext() {
        return new ReviewerContext(reviewerId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));
    }

    @Test
    void submitterCannotApproveOwnGroup() {
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        var selfContext = new ReviewerContext(submitterId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));

        assertThatThrownBy(() -> service.approve(group.getId(), selfContext, "ok"))
                .isInstanceOf(SelfApprovalNotAllowedException.class);
    }

    @Test
    void approveAdvancesToApprovedWhenThresholdMet() {
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        when(decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(group.getId(), reviewerId, 1))
                .thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            GroupReviewDecisionEntity d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(decisionRepository.countByRequestGroupIdAndStageAndDecision(
                group.getId(), 1, DecisionType.APPROVED)).thenReturn(1L);

        var outcome = service.approve(group.getId(), adminContext(), "lgtm");

        assertThat(outcome.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(outcome.resultingStatus()).isEqualTo(RequestGroupStatus.APPROVED);
        assertThat(outcome.wasIdempotentReplay()).isFalse();
        verify(stateService).apply(group, RequestGroupStatus.APPROVED);
    }

    @Test
    void approveIsIdempotentOnReplay() {
        var existing = new GroupReviewDecisionEntity();
        existing.setId(UUID.randomUUID());
        existing.setDecision(DecisionType.APPROVED);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        when(decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(group.getId(), reviewerId, 1))
                .thenReturn(Optional.of(existing));

        var outcome = service.approve(group.getId(), adminContext(), "again");

        assertThat(outcome.wasIdempotentReplay()).isTrue();
        verify(stateService, org.mockito.Mockito.never())
                .apply(any(), eq(RequestGroupStatus.APPROVED));
    }

    @Test
    void rejectTransitionsToRejected() {
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        when(decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(group.getId(), reviewerId, 1))
                .thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            GroupReviewDecisionEntity d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        var outcome = service.reject(group.getId(), adminContext(), "no");

        assertThat(outcome.resultingStatus()).isEqualTo(RequestGroupStatus.REJECTED);
        verify(stateService).apply(group, RequestGroupStatus.REJECTED);
    }

    @Test
    void listPendingMapsGroupsToPendingReviews() {
        group.setName("bundle");
        when(groupRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<
                        com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity>>any(),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(group)));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(java.util.List.of(new com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity()));
        when(userQueryService.findById(submitterId)).thenReturn(java.util.Optional.empty());

        var page = service.listPending(adminContext(), com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).name()).isEqualTo("bundle");
        assertThat(page.content().get(0).memberCount()).isEqualTo(1);
    }

    @Test
    void nonAdminReviewerEligibleByRoleCanApprove() {
        var reviewerContext = new ReviewerContext(reviewerId, orgId, "REVIEWER",
                SystemRolePermissions.of(UserRoleType.REVIEWER));
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(java.util.List.of());
        when(reviewPlanResolver.resolve(any(), any())).thenReturn(
                new GroupReviewPlanResolver.GroupReviewResolution(true, 1,
                        java.util.Set.of(), java.util.Set.of("REVIEWER")));
        when(decisionRepository.findByRequestGroupIdAndReviewerIdAndStage(group.getId(), reviewerId, 1))
                .thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(inv -> {
            GroupReviewDecisionEntity d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(decisionRepository.countByRequestGroupIdAndStageAndDecision(group.getId(), 1,
                DecisionType.APPROVED)).thenReturn(1L);

        var outcome = service.approve(group.getId(), reviewerContext, "ok");

        assertThat(outcome.resultingStatus()).isEqualTo(RequestGroupStatus.APPROVED);
    }

    @Test
    void ineligibleReviewerIsRejected() {
        var reviewerContext = new ReviewerContext(reviewerId, orgId, "REVIEWER",
                SystemRolePermissions.of(UserRoleType.REVIEWER));
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        when(itemRepository.findByGroupIdOrderBySequenceOrderAsc(group.getId()))
                .thenReturn(java.util.List.of());
        when(reviewPlanResolver.resolve(any(), any())).thenReturn(
                new GroupReviewPlanResolver.GroupReviewResolution(true, 1,
                        java.util.Set.of(), java.util.Set.of("ADMIN")));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.approve(group.getId(), reviewerContext, "ok"))
                .isInstanceOf(com.bablsoft.accessflow.requestgroups.api.RequestGroupPermissionException.class);
    }

    @Test
    void approveOnNonPendingGroupThrows() {
        group.setStatus(RequestGroupStatus.APPROVED);
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.approve(group.getId(), adminContext(), "ok"))
                .isInstanceOf(com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException.class);
    }
}
