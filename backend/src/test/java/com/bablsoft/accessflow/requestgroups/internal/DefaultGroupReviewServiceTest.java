package com.bablsoft.accessflow.requestgroups.internal;

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
        return new ReviewerContext(reviewerId, orgId, UserRoleType.ADMIN);
    }

    @Test
    void submitterCannotApproveOwnGroup() {
        when(groupRepository.findByIdAndOrganizationId(group.getId(), orgId))
                .thenReturn(Optional.of(group));
        var selfContext = new ReviewerContext(submitterId, orgId, UserRoleType.ADMIN);

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
}
