package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.bablsoft.accessflow.core.api.PendingReviewView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.workflow.api.ExternalTicketDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultExternalDecisionServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock ApplicationEventPublisher eventPublisher;

    private DefaultExternalDecisionService service;

    private final UUID queryRequestId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultExternalDecisionService(queryRequestLookupService,
                queryRequestStateService, eventPublisher);
    }

    @Test
    void approveTransitionsToApprovedAndPublishesAutoApprovedEvent() {
        when(queryRequestLookupService.findPendingReview(queryRequestId))
                .thenReturn(Optional.of(view(QueryStatus.PENDING_REVIEW)));

        var applied = service.applyTicketDecision(queryRequestId, orgId,
                ExternalTicketDecision.APPROVE, "ServiceNow ticket INC1 resolved");

        assertThat(applied).isTrue();
        verify(queryRequestStateService).transitionTo(queryRequestId,
                QueryStatus.PENDING_REVIEW, QueryStatus.APPROVED);
        verify(eventPublisher).publishEvent(new QueryAutoApprovedEvent(queryRequestId, null,
                "ServiceNow ticket INC1 resolved", null, null));
    }

    @Test
    void rejectTransitionsToRejectedAndPublishesAutoRejectedEvent() {
        when(queryRequestLookupService.findPendingReview(queryRequestId))
                .thenReturn(Optional.of(view(QueryStatus.PENDING_REVIEW)));

        var applied = service.applyTicketDecision(queryRequestId, orgId,
                ExternalTicketDecision.REJECT, "Jira ticket AF-1 declined");

        assertThat(applied).isTrue();
        verify(queryRequestStateService).transitionTo(queryRequestId,
                QueryStatus.PENDING_REVIEW, QueryStatus.REJECTED);
        verify(eventPublisher).publishEvent(new QueryAutoRejectedEvent(queryRequestId, null,
                "Jira ticket AF-1 declined"));
    }

    @Test
    void returnsFalseWhenQueryUnknown() {
        when(queryRequestLookupService.findPendingReview(queryRequestId))
                .thenReturn(Optional.empty());

        var applied = service.applyTicketDecision(queryRequestId, orgId,
                ExternalTicketDecision.APPROVE, "r");

        assertThat(applied).isFalse();
        verifyNoInteractions(queryRequestStateService, eventPublisher);
    }

    @Test
    void returnsFalseOnOrganizationMismatch() {
        when(queryRequestLookupService.findPendingReview(queryRequestId))
                .thenReturn(Optional.of(view(QueryStatus.PENDING_REVIEW)));

        var applied = service.applyTicketDecision(queryRequestId, UUID.randomUUID(),
                ExternalTicketDecision.APPROVE, "r");

        assertThat(applied).isFalse();
        verifyNoInteractions(queryRequestStateService, eventPublisher);
    }

    @Test
    void returnsFalseWhenNoLongerPendingReview() {
        when(queryRequestLookupService.findPendingReview(queryRequestId))
                .thenReturn(Optional.of(view(QueryStatus.APPROVED)));

        var applied = service.applyTicketDecision(queryRequestId, orgId,
                ExternalTicketDecision.REJECT, "r");

        assertThat(applied).isFalse();
        verifyNoInteractions(queryRequestStateService, eventPublisher);
    }

    @Test
    void returnsFalseWhenTransitionRacesManualDecision() {
        when(queryRequestLookupService.findPendingReview(queryRequestId))
                .thenReturn(Optional.of(view(QueryStatus.PENDING_REVIEW)));
        doThrow(new IllegalQueryStatusTransitionException(queryRequestId,
                QueryStatus.APPROVED, QueryStatus.PENDING_REVIEW))
                .when(queryRequestStateService)
                .transitionTo(queryRequestId, QueryStatus.PENDING_REVIEW, QueryStatus.APPROVED);

        var applied = service.applyTicketDecision(queryRequestId, orgId,
                ExternalTicketDecision.APPROVE, "r");

        assertThat(applied).isFalse();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectTransitionRaceAlsoReturnsFalseWithoutEvent() {
        when(queryRequestLookupService.findPendingReview(queryRequestId))
                .thenReturn(Optional.of(view(QueryStatus.PENDING_REVIEW)));
        doThrow(new IllegalQueryStatusTransitionException(queryRequestId,
                QueryStatus.REJECTED, QueryStatus.PENDING_REVIEW))
                .when(queryRequestStateService)
                .transitionTo(queryRequestId, QueryStatus.PENDING_REVIEW, QueryStatus.REJECTED);

        var applied = service.applyTicketDecision(queryRequestId, orgId,
                ExternalTicketDecision.REJECT, "r");

        assertThat(applied).isFalse();
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(Object.class));
    }

    private PendingReviewView view(QueryStatus status) {
        return new PendingReviewView(queryRequestId, UUID.randomUUID(), "Production", orgId,
                UUID.randomUUID(), "alice@example.com", "SELECT 1", QueryType.SELECT, status,
                "need it", null, null, null, null, Instant.now());
    }
}
