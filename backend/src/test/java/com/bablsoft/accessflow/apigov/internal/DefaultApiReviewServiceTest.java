package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiReviewService.ReviewerContext;
import com.bablsoft.accessflow.apigov.api.SelfApprovalNotAllowedException;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiReviewDecisionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiReviewDecisionRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiReviewServiceTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiReviewDecisionRepository decisionRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiRequestStateService stateService;
    @Mock private AiAnalysisLookupService aiAnalysisLookupService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private DefaultApiReviewService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiReviewService(requestRepository, decisionRepository, connectorRepository,
                stateService, aiAnalysisLookupService, eventPublisher);
    }

    private ApiRequestEntity pending() {
        var e = new ApiRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(orgId);
        e.setSubmittedBy(submitterId);
        e.setStatus(QueryStatus.PENDING_REVIEW);
        e.setRequiredApprovals(1);
        return e;
    }

    private ReviewerContext reviewer() {
        return new ReviewerContext(reviewerId, orgId, UserRoleType.REVIEWER);
    }

    @Test
    void approveReachingThresholdTransitionsToApproved() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId)).thenReturn(Optional.of(pending()));
        when(decisionRepository.findByApiRequestIdAndReviewerIdAndStage(requestId, reviewerId, 1))
                .thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(i -> { var d = (ApiReviewDecisionEntity) i.getArgument(0); d.setId(UUID.randomUUID()); return d; });
        when(decisionRepository.countByApiRequestIdAndStageAndDecision(requestId, 1, DecisionType.APPROVED))
                .thenReturn(1L);

        var outcome = service.approve(requestId, reviewer(), "ok");

        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(outcome.wasIdempotentReplay()).isFalse();
        verify(stateService).apply(any(), eq(QueryStatus.APPROVED));
    }

    @Test
    void submitterCannotSelfApprove() {
        var own = pending();
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId)).thenReturn(Optional.of(own));

        var ctx = new ReviewerContext(submitterId, orgId, UserRoleType.ADMIN);
        assertThatThrownBy(() -> service.approve(requestId, ctx, "x"))
                .isInstanceOf(SelfApprovalNotAllowedException.class);
        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void approveIsIdempotentOnReplay() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId)).thenReturn(Optional.of(pending()));
        var existing = new ApiReviewDecisionEntity();
        existing.setId(UUID.randomUUID());
        existing.setDecision(DecisionType.APPROVED);
        when(decisionRepository.findByApiRequestIdAndReviewerIdAndStage(requestId, reviewerId, 1))
                .thenReturn(Optional.of(existing));

        var outcome = service.approve(requestId, reviewer(), "again");

        assertThat(outcome.wasIdempotentReplay()).isTrue();
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void rejectTransitionsToRejected() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId)).thenReturn(Optional.of(pending()));
        when(decisionRepository.findByApiRequestIdAndReviewerIdAndStage(requestId, reviewerId, 1))
                .thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(i -> { var d = (ApiReviewDecisionEntity) i.getArgument(0); d.setId(UUID.randomUUID()); return d; });

        var outcome = service.reject(requestId, reviewer(), "no");

        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        verify(stateService).apply(any(), eq(QueryStatus.REJECTED));
    }

    @Test
    void cannotReviewNonPendingRequest() {
        var executed = pending();
        executed.setStatus(QueryStatus.EXECUTED);
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId)).thenReturn(Optional.of(executed));

        assertThatThrownBy(() -> service.approve(requestId, reviewer(), "x"))
                .isInstanceOf(IllegalApiRequestStateException.class);
    }

    @Test
    void listPendingExcludesOwnRequests() {
        var mine = pending();
        var other = pending();
        other.setId(UUID.randomUUID());
        other.setSubmittedBy(UUID.randomUUID());
        mine.setSubmittedBy(reviewerId);
        when(requestRepository.findByOrganizationIdAndStatus(eq(orgId), eq(QueryStatus.PENDING_REVIEW), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(mine, other)));
        lenient().when(connectorRepository.findById(any())).thenReturn(Optional.empty());

        var page = service.listPending(reviewer(), com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).submittedByUserId()).isEqualTo(other.getSubmittedBy());
    }
}
