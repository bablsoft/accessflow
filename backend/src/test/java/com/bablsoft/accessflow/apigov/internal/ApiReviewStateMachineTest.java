package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRoutingAction;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestReadyForReviewEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.StepOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The applier half of the split (issue AF-967). Every rule about routing policies and the connector's
 * review settings is exercised in {@link ApiDecisionEvaluatorTest}; what is asserted here is that each
 * decision kind produces the right transition, approval count and event.
 */
@ExtendWith(MockitoExtension.class)
class ApiReviewStateMachineTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiDecisionEvaluator decisionEvaluator;
    @Mock private ApiRequestStateService stateService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ApiReviewStateMachine machine;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        machine = new ApiReviewStateMachine(requestRepository, connectorRepository, decisionEvaluator,
                stateService, eventPublisher);
    }

    private ApiRequestEntity pendingAi(boolean write) {
        var e = new ApiRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setSubmittedBy(UUID.randomUUID());
        e.setVerb(write ? "POST" : "GET");
        e.setWrite(write);
        e.setStatus(QueryStatus.PENDING_AI);
        return e;
    }

    private ApiConnectorEntity connector(boolean requireReads, boolean requireWrites) {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setOrganizationId(orgId);
        c.setProtocol(ApiProtocol.REST);
        c.setRequireReviewReads(requireReads);
        c.setRequireReviewWrites(requireWrites);
        return c;
    }

    private void loaded(boolean write) {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pendingAi(write)));
        when(connectorRepository.findById(connectorId))
                .thenReturn(Optional.of(connector(false, true)));
    }

    private static ApiDecision decision(ApiDecisionKind kind, QueryStatus status,
                                        ApiRoutingPolicyEngine.RoutingMatch match,
                                        Integer approvals) {
        var steps = List.of(DecisionTraceStep.of(ApiDecisionStepKind.ROUTING_POLICIES,
                StepOutcome.NO_MATCH, "apigov.decision.routing.no_match"));
        return new ApiDecision(kind, status, match, approvals, new DecisionTrace(steps, status));
    }

    private ApiRoutingPolicyEngine.RoutingMatch match(ApiRoutingAction action) {
        return new ApiRoutingPolicyEngine.RoutingMatch(policyId, "policy", action, null);
    }

    // ── The evaluator drives, the machine applies ─────────────────────────────

    @Test
    void theEvaluatorSeesTheAnalysisOutcomeAndTheConnectorsGovernanceFields() {
        loaded(true);
        when(decisionEvaluator.evaluate(any())).thenReturn(
                decision(ApiDecisionKind.CONNECTOR_PENDING_REVIEW, QueryStatus.PENDING_REVIEW,
                        null, 2));

        machine.decide(requestId, AiOutcome.COMPLETED, RiskLevel.HIGH);

        var captor = ArgumentCaptor.forClass(ApiDecisionInput.class);
        verify(decisionEvaluator).evaluate(captor.capture());
        assertThat(captor.getValue().aiOutcome()).isEqualTo(AiOutcome.COMPLETED);
        assertThat(captor.getValue().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(captor.getValue().write()).isTrue();
        assertThat(captor.getValue().requireReviewWrites()).isTrue();
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
    }

    @Test
    void connectorApprovedTransitionsAndPublishesWithoutAPolicyTag() {
        loaded(false);
        when(decisionEvaluator.evaluate(any())).thenReturn(
                decision(ApiDecisionKind.CONNECTOR_APPROVED, QueryStatus.APPROVED, null, null));

        machine.decide(requestId, AiOutcome.SKIPPED, null);

        verify(stateService).apply(any(), eq(QueryStatus.APPROVED));
        var event = ArgumentCaptor.forClass(ApiRequestDecidedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reason()).isNull();
    }

    @Test
    void routingAutoApproveTagsTheEventWithTheDecidingPolicy() {
        loaded(false);
        when(decisionEvaluator.evaluate(any())).thenReturn(
                decision(ApiDecisionKind.ROUTING_AUTO_APPROVE, QueryStatus.APPROVED,
                        match(ApiRoutingAction.AUTO_APPROVE), null));

        machine.decide(requestId, AiOutcome.COMPLETED, RiskLevel.LOW);

        verify(stateService).apply(any(), eq(QueryStatus.APPROVED));
        var event = ArgumentCaptor.forClass(ApiRequestDecidedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reason()).isEqualTo("routing:" + policyId);
    }

    @Test
    void routingAutoRejectTransitionsToRejectedAndTagsTheEvent() {
        loaded(true);
        when(decisionEvaluator.evaluate(any())).thenReturn(
                decision(ApiDecisionKind.ROUTING_AUTO_REJECT, QueryStatus.REJECTED,
                        match(ApiRoutingAction.AUTO_REJECT), null));

        machine.decide(requestId, AiOutcome.COMPLETED, RiskLevel.CRITICAL);

        verify(stateService).apply(any(), eq(QueryStatus.REJECTED));
        var event = ArgumentCaptor.forClass(ApiRequestDecidedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().status()).isEqualTo(QueryStatus.REJECTED);
        assertThat(event.getValue().reason()).isEqualTo("routing:" + policyId);
    }

    @Test
    void everyReviewKindRecordsTheEvaluatorsApprovalCount() {
        for (var kind : List.of(ApiDecisionKind.ROUTING_REQUIRE_APPROVALS,
                ApiDecisionKind.ROUTING_ESCALATE, ApiDecisionKind.CONNECTOR_PENDING_REVIEW,
                ApiDecisionKind.AI_FAILED_PENDING_REVIEW)) {
            var request = pendingAi(true);
            when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
            when(connectorRepository.findById(connectorId))
                    .thenReturn(Optional.of(connector(false, true)));
            when(decisionEvaluator.evaluate(any())).thenReturn(
                    decision(kind, QueryStatus.PENDING_REVIEW, null, 3));

            machine.decide(requestId, AiOutcome.SKIPPED, null);

            assertThat(request.getRequiredApprovals()).as("%s", kind).isEqualTo(3);
        }
    }

    @Test
    void aReviewDecisionWithNoCountFallsBackToOneApproval() {
        var request = pendingAi(true);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(connectorRepository.findById(connectorId))
                .thenReturn(Optional.of(connector(false, true)));
        when(decisionEvaluator.evaluate(any())).thenReturn(
                decision(ApiDecisionKind.CONNECTOR_PENDING_REVIEW, QueryStatus.PENDING_REVIEW,
                        null, null));

        machine.decide(requestId, AiOutcome.SKIPPED, null);

        assertThat(request.getRequiredApprovals()).isEqualTo(1);
        var event = ArgumentCaptor.forClass(ApiRequestReadyForReviewEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().requiredApprovals()).isEqualTo(1);
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    @Test
    void ignoresRequestNotInPendingAi() {
        var executed = pendingAi(false);
        executed.setStatus(QueryStatus.EXECUTED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(executed));

        machine.decide(requestId, AiOutcome.COMPLETED, RiskLevel.LOW);

        verify(stateService, never()).apply(any(), any());
        verify(decisionEvaluator, never()).evaluate(any());
    }

    @Test
    void ignoresARequestThatNoLongerExists() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());

        machine.decide(requestId, AiOutcome.COMPLETED, RiskLevel.LOW);

        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void aDeletedConnectorFallsBackToOneHumanApprovalWithoutConsultingTheEvaluator() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pendingAi(true)));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.empty());

        machine.decide(requestId, AiOutcome.COMPLETED, RiskLevel.HIGH);

        verify(stateService).apply(any(), eq(QueryStatus.PENDING_REVIEW));
        verify(decisionEvaluator, never()).evaluate(any());
    }
}
