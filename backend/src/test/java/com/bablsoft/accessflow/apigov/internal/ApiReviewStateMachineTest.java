package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRoutingAction;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiReviewStateMachineTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiRoutingPolicyEngine routingEngine;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;
    @Mock private ApiRequestStateService stateService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ApiReviewStateMachine machine;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        machine = new ApiReviewStateMachine(requestRepository, connectorRepository, routingEngine,
                reviewPlanLookupService, stateService, eventPublisher);
        lenient().when(reviewPlanLookupService.findById(any())).thenReturn(Optional.empty());
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

    @Test
    void autoApprovesReadWhenReviewNotRequired() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pendingAi(false)));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(false, true)));
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(null);

        machine.decide(requestId, RiskLevel.LOW);

        verify(stateService).apply(any(), eq(QueryStatus.APPROVED));
    }

    @Test
    void routesWriteToReviewWhenRequired() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pendingAi(true)));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(false, true)));
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(null);

        machine.decide(requestId, RiskLevel.MEDIUM);

        verify(stateService).apply(any(), eq(QueryStatus.PENDING_REVIEW));
    }

    @Test
    void routingAutoRejectTransitionsToRejected() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pendingAi(true)));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(false, true)));
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(
                new ApiRoutingPolicyEngine.RoutingMatch(UUID.randomUUID(), ApiRoutingAction.AUTO_REJECT, null));

        machine.decide(requestId, RiskLevel.CRITICAL);

        verify(stateService).apply(any(), eq(QueryStatus.REJECTED));
    }

    @Test
    void failedAnalysisForcesReview() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(pendingAi(false)));
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector(false, false)));

        machine.forceReview(requestId);

        verify(stateService).apply(any(), eq(QueryStatus.PENDING_REVIEW));
    }

    @Test
    void ignoresRequestNotInPendingAi() {
        var executed = pendingAi(false);
        executed.setStatus(QueryStatus.EXECUTED);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(executed));

        machine.decide(requestId, RiskLevel.LOW);

        verify(stateService, org.mockito.Mockito.never()).apply(any(), any());
    }
}
