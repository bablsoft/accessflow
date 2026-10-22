package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentExternalDecisionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentTicketProvenance;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * External-ticket decisions on deployments (#992). System-attributed like the routing and timeout
 * paths: no decision row, a null-actor audit row with {@code trigger = ticket}, and a
 * {@link DeploymentDecidedEvent} whose reason is {@code ticket:<SYSTEM>:<external_key>} so the
 * submitter is notified exactly as for any other system verdict.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DefaultDeploymentExternalDecisionService implements DeploymentExternalDecisionService {

    private final DeploymentRequestRepository repository;
    private final DeploymentRequestStateService stateService;
    private final DeploygovAuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public boolean applyTicketDecision(UUID deploymentRequestId, UUID organizationId,
                                       DecisionType decision,
                                       DeploymentTicketProvenance provenance) {
        var next = switch (decision) {
            case APPROVED -> QueryStatus.APPROVED;
            case REJECTED -> QueryStatus.REJECTED;
            case REQUESTED_CHANGES -> throw new IllegalArgumentException(
                    "A ticket can only approve or reject a deployment");
        };
        var request = repository.findByIdForUpdate(deploymentRequestId).orElse(null);
        if (request == null || !request.getOrganizationId().equals(organizationId)
                || request.getStatus() != QueryStatus.PENDING_REVIEW) {
            return false;
        }
        try {
            stateService.apply(request, next);
        } catch (IllegalDeploymentRequestStateException ex) {
            log.debug("External ticket decision raced a manual decision on deployment {}: {}",
                    deploymentRequestId, ex.getMessage());
            return false;
        }
        audit(request, next == QueryStatus.APPROVED
                ? AuditAction.DEPLOYMENT_APPROVED : AuditAction.DEPLOYMENT_REJECTED, provenance);
        eventPublisher.publishEvent(new DeploymentDecidedEvent(deploymentRequestId, next,
                "ticket:" + provenance.ticketSystem() + ":" + provenance.externalKey()));
        log.info("Applied external ticket decision {} to deployment {} ({})", decision,
                deploymentRequestId, provenance.reason());
        return true;
    }

    private void audit(DeploymentRequestEntity request, AuditAction action,
                       DeploymentTicketProvenance provenance) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "ticket");
        metadata.put("ticket_system", provenance.ticketSystem());
        metadata.put("external_key", provenance.externalKey());
        metadata.put("channel_id", provenance.channelId().toString());
        if (provenance.actor() != null) {
            metadata.put("actor", provenance.actor());
        }
        metadata.put("pipeline_id", request.getPipelineId().toString());
        metadata.put("version", request.getVersion());
        auditWriter.record(action, AuditResourceType.DEPLOYMENT_REQUEST, request.getId(),
                request.getOrganizationId(), null, metadata, null);
    }
}
