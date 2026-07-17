package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.IllegalQueryStatusTransitionException;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.workflow.api.ExternalDecisionService;
import com.bablsoft.accessflow.workflow.api.ExternalTicketDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * External-ticket decisions (AF-453). Publishes the existing auto-decision core events so the
 * downstream audit and notification listeners fire exactly as they do for routing-policy
 * decisions — the {@code reason} carries the ticket provenance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DefaultExternalDecisionService implements ExternalDecisionService {

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public boolean applyTicketDecision(UUID queryRequestId, UUID organizationId,
                                       ExternalTicketDecision decision, String reason) {
        var view = queryRequestLookupService.findPendingReview(queryRequestId).orElse(null);
        if (view == null || !view.organizationId().equals(organizationId)
                || view.status() != QueryStatus.PENDING_REVIEW) {
            return false;
        }
        try {
            if (decision == ExternalTicketDecision.APPROVE) {
                queryRequestStateService.transitionTo(queryRequestId, QueryStatus.PENDING_REVIEW,
                        QueryStatus.APPROVED);
                eventPublisher.publishEvent(
                        new QueryAutoApprovedEvent(queryRequestId, null, reason, null, null));
            } else {
                queryRequestStateService.transitionTo(queryRequestId, QueryStatus.PENDING_REVIEW,
                        QueryStatus.REJECTED);
                eventPublisher.publishEvent(new QueryAutoRejectedEvent(queryRequestId, null, reason));
            }
        } catch (IllegalQueryStatusTransitionException ex) {
            log.debug("External ticket decision raced a manual decision on query {}: {}",
                    queryRequestId, ex.getMessage());
            return false;
        }
        log.info("Applied external ticket decision {} to query {} ({})", decision, queryRequestId,
                reason);
        return true;
    }
}
