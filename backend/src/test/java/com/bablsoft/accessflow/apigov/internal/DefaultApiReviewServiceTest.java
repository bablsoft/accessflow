package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.core.api.SystemRolePermissions;
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
    @Mock private com.bablsoft.accessflow.core.api.ReviewPlanLookupService reviewPlanLookupService;
    @Mock private com.bablsoft.accessflow.core.api.ReviewDelegationLookupService reviewDelegationLookupService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private DefaultApiReviewService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiReviewService(requestRepository, decisionRepository, connectorRepository,
                stateService, aiAnalysisLookupService, reviewPlanLookupService,
                reviewDelegationLookupService, eventPublisher,
                tools.jackson.databind.json.JsonMapper.builder().build());
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
        return new ReviewerContext(reviewerId, orgId, "REVIEWER",
                SystemRolePermissions.of(UserRoleType.REVIEWER));
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

        var ctx = new ReviewerContext(submitterId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));
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
    void listPendingMapsPageFromSpecification() {
        // Self-exclusion + connector/verb narrowing live in the JPA Specification (covered by
        // ApiRequestSpecificationsTest); here we just verify the spec-backed page maps to views.
        var other = pending();
        other.setId(UUID.randomUUID());
        other.setSubmittedBy(UUID.randomUUID());
        when(requestRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<ApiRequestEntity>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(other)));
        lenient().when(connectorRepository.findById(any())).thenReturn(Optional.empty());

        var filter = new com.bablsoft.accessflow.apigov.api.ApiReviewService.PendingApiReviewFilter(null, null);
        var page = service.listPending(reviewer(), filter,
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).submittedByUserId()).isEqualTo(other.getSubmittedBy());
    }

    // ------------------------------------- approver eligibility + delegation (#622)

    private final UUID connectorId = UUID.randomUUID();
    private final UUID delegatorId = UUID.randomUUID();

    private ApiRequestEntity pendingOnConnector() {
        var e = pending();
        e.setConnectorId(connectorId);
        return e;
    }

    private void givenConnectorPlan(com.bablsoft.accessflow.core.api.ApproverRule... rules) {
        var planId = UUID.randomUUID();
        var connector = new com.bablsoft.accessflow.apigov.internal.persistence.entity
                .ApiConnectorEntity();
        connector.setId(connectorId);
        connector.setReviewPlanId(planId);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector));
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(
                new com.bablsoft.accessflow.core.api.ReviewPlanSnapshot(planId, orgId, true, true,
                        1, false, 1, java.util.List.of(rules), java.util.List.of())));
    }

    @Test
    void aConnectorWithNoReviewPlanStaysOpenToAnyPermittedReviewer() {
        // The pre-#622 behaviour, deliberately preserved: treating "no plan" as "nobody is an
        // approver" would make every un-planned connector unreviewable on upgrade.
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pendingOnConnector()));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.empty());
        when(decisionRepository.findByApiRequestIdAndReviewerIdAndStage(requestId, reviewerId, 1))
                .thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(i -> {
            var d = (ApiReviewDecisionEntity) i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(decisionRepository.countByApiRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);

        assertThat(service.approve(requestId, reviewer(), "ok").resultingStatus())
                .isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void aPlanWithApproverRulesNowGatesWhoMayDecide() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pendingOnConnector()));
        // The plan routes to ADMIN only; the caller is a REVIEWER holding API_REQUEST_REVIEW.
        givenConnectorPlan(new com.bablsoft.accessflow.core.api.ApproverRule(null, "ADMIN", 1));
        when(decisionRepository.findAllByApiRequestIdAndStage(requestId, 1))
                .thenReturn(java.util.List.of());
        when(reviewDelegationLookupService.findActiveForDelegate(any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.approve(requestId, reviewer(), "ok"))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiReviewerNotEligibleException.class);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void aDelegateApprovesUnderTheDelegatorsRuleAndRecordsBothIdentities() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pendingOnConnector()));
        givenConnectorPlan(new com.bablsoft.accessflow.core.api.ApproverRule(delegatorId, null, 1));
        when(decisionRepository.findAllByApiRequestIdAndStage(requestId, 1))
                .thenReturn(java.util.List.of());
        var delegationId = UUID.randomUUID();
        when(reviewDelegationLookupService.findActiveForDelegate(eq(orgId), eq(reviewerId), any(), any()))
                .thenReturn(java.util.List.of(new com.bablsoft.accessflow.core.api.DelegatedIdentity(
                        delegationId, delegatorId, "REVIEWER", null, null)));
        when(decisionRepository.findByApiRequestIdAndReviewerIdAndStage(requestId, reviewerId, 1))
                .thenReturn(Optional.empty());
        var saved = new java.util.concurrent.atomic.AtomicReference<ApiReviewDecisionEntity>();
        when(decisionRepository.save(any())).thenAnswer(i -> {
            var d = (ApiReviewDecisionEntity) i.getArgument(0);
            d.setId(UUID.randomUUID());
            saved.set(d);
            return d;
        });
        when(decisionRepository.countByApiRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);

        service.approve(requestId, reviewer(), "covering");

        assertThat(saved.get().getReviewerId()).isEqualTo(reviewerId);
        assertThat(saved.get().getOnBehalfOfUserId()).isEqualTo(delegatorId);
        assertThat(saved.get().getDelegationId()).isEqualTo(delegationId);
    }

    @Test
    void aDelegateCannotActOnARequestTheDelegatorSubmitted() {
        var request = pendingOnConnector();
        request.setSubmittedBy(delegatorId);
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(request));
        givenConnectorPlan(new com.bablsoft.accessflow.core.api.ApproverRule(delegatorId, null, 1));
        when(decisionRepository.findAllByApiRequestIdAndStage(requestId, 1))
                .thenReturn(java.util.List.of());
        when(reviewDelegationLookupService.findActiveForDelegate(eq(orgId), eq(reviewerId), any(), any()))
                .thenReturn(java.util.List.of(new com.bablsoft.accessflow.core.api.DelegatedIdentity(
                        UUID.randomUUID(), delegatorId, "REVIEWER", null, null)));

        assertThatThrownBy(() -> service.approve(requestId, reviewer(), "ok"))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiReviewerNotEligibleException.class);
    }

    @Test
    void aDelegateCannotAddASecondVoteForADelegatorWhoAlreadyDecided() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pendingOnConnector()));
        givenConnectorPlan(new com.bablsoft.accessflow.core.api.ApproverRule(delegatorId, null, 1));
        var alreadyVoted = new ApiReviewDecisionEntity();
        alreadyVoted.setReviewerId(delegatorId);
        when(decisionRepository.findAllByApiRequestIdAndStage(requestId, 1))
                .thenReturn(java.util.List.of(alreadyVoted));
        when(reviewDelegationLookupService.findActiveForDelegate(eq(orgId), eq(reviewerId), any(), any()))
                .thenReturn(java.util.List.of(new com.bablsoft.accessflow.core.api.DelegatedIdentity(
                        UUID.randomUUID(), delegatorId, "REVIEWER", null, null)));

        assertThatThrownBy(() -> service.approve(requestId, reviewer(), "ok"))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiReviewerNotEligibleException.class);
    }

    @Test
    void reviewOverrideBypassesApproverRules() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pendingOnConnector()));
        when(decisionRepository.findByApiRequestIdAndReviewerIdAndStage(requestId, reviewerId, 1))
                .thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(i -> {
            var d = (ApiReviewDecisionEntity) i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(decisionRepository.countByApiRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);
        var admin = new ReviewerContext(reviewerId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));

        assertThat(service.approve(requestId, admin, "ok").resultingStatus())
                .isEqualTo(QueryStatus.APPROVED);
        // The plan is never consulted for an override holder.
        verify(connectorRepository, never()).findById(any());
    }

    @Test
    void aCallerWithoutTheReviewPermissionIsRejectedInTheServiceNotJustTheController() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pendingOnConnector()));
        var analyst = new ReviewerContext(reviewerId, orgId, "ANALYST",
                SystemRolePermissions.of(UserRoleType.ANALYST));

        assertThatThrownBy(() -> service.approve(requestId, analyst, "ok"))
                .isInstanceOf(com.bablsoft.accessflow.apigov.api.ApiReviewerNotEligibleException.class);
        verify(reviewDelegationLookupService, never()).findActiveForDelegate(any(), any(), any(), any());
    }

    @Test
    void listPendingIsEmptyWithoutTheReviewPermission() {
        var analyst = new ReviewerContext(reviewerId, orgId, "ANALYST",
                SystemRolePermissions.of(UserRoleType.ANALYST));

        var page = service.listPending(analyst,
                new com.bablsoft.accessflow.apigov.api.ApiReviewService.PendingApiReviewFilter(null, null),
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        verify(requestRepository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }
}
