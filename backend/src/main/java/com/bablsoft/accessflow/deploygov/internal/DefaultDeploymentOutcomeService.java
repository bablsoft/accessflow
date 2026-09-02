package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeConflictException;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestPermissionException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentOutcomeReportedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRollbackReviewEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRollbackReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Post-execution outcome recording (#693). Idempotent on the reported outcome; a {@code FAILED}
 * outcome performs the one post-terminal flip {@code EXECUTED → FAILED}; a {@code ROLLED_BACK}
 * outcome on an environment with {@code require_review = true} opens the rollback follow-up
 * review in the same transaction, so a governed rollback can never exist without its review row.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentOutcomeService implements DeploymentOutcomeService {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentRollbackReviewRepository rollbackReviewRepository;
    private final EffectiveDeploymentPermissionResolver permissionResolver;
    private final DeploymentRequestStateService stateService;
    private final DefaultDeploymentRequestService requestService;
    private final DeploygovAuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final DeploymentVersionTrackerService versionTracker;
    private final Clock clock;

    @Override
    @Transactional
    public DeploymentRequestView reportOutcome(UUID requestId, DeploymentOutcome outcome,
                                               String detail, UUID organizationId, UUID callerId,
                                               Set<Permission> callerPermissions,
                                               String ipAddress) {
        var request = requestRepository.findByIdAndOrganizationId(requestId, organizationId)
                .orElseThrow(() -> new DeploymentRequestNotFoundException(requestId));
        requireActor(request, callerId, callerPermissions);
        // EXECUTED is the normal entry; FAILED-with-an-outcome is the post-flip state, so a
        // redelivered FAILED report still resolves idempotently instead of 409ing on status.
        boolean reportable = request.getStatus() == QueryStatus.EXECUTED
                || (request.getStatus() == QueryStatus.FAILED && request.getOutcome() != null);
        if (!reportable) {
            throw new IllegalDeploymentRequestStateException(request.getStatus(),
                    "Deployment outcome can only be reported after execution");
        }
        if (request.getOutcome() != null) {
            if (request.getOutcome() == outcome) {
                return requestService.detailView(request);
            }
            throw new DeploymentOutcomeConflictException(requestId, request.getOutcome(), outcome);
        }
        // Two concurrent FIRST reports of different outcomes both pass the null guard; the loser
        // then fails on the @Version optimistic lock rather than this 409 — an acceptable rare
        // race, since the retry resolves to the idempotent/conflict branch above.

        request.setOutcome(outcome);
        request.setOutcomeDetail(detail);
        request.setOutcomeReportedAt(clock.instant());
        if (outcome == DeploymentOutcome.FAILED) {
            stateService.apply(request, QueryStatus.FAILED);
        } else {
            requestRepository.save(request);
        }
        // #741: reached only on a first report — the idempotent/conflict branches exit earlier.
        versionTracker.recordOutcome(request, outcome);
        boolean rollbackReviewOpened = outcome == DeploymentOutcome.ROLLED_BACK
                && maybeOpenRollbackReview(request, detail);
        auditWriter.record(AuditAction.DEPLOYMENT_OUTCOME_REPORTED,
                AuditResourceType.DEPLOYMENT_REQUEST, request.getId(), organizationId, callerId,
                Map.of("trigger", "pipeline",
                        "outcome", outcome.name(),
                        "rollback_review_opened", rollbackReviewOpened),
                ipAddress);
        eventPublisher.publishEvent(new DeploymentOutcomeReportedEvent(organizationId,
                request.getId(), request.getPipelineId(), outcome, detail));
        return requestService.detailView(request);
    }

    /**
     * Open the follow-up review when the environment demands one. The UNIQUE
     * {@code deployment_request_id} column is the concurrency backstop; the outcome-null guard
     * above already makes a second report unreachable here.
     */
    private boolean maybeOpenRollbackReview(DeploymentRequestEntity request, String detail) {
        boolean requireReview = environmentRepository.findById(request.getEnvironmentId())
                .map(DeploymentEnvironmentEntity::isRequireReview)
                .orElse(true);
        if (!requireReview) {
            return false;
        }
        var review = new DeploymentRollbackReviewEntity();
        review.setId(UUID.randomUUID());
        review.setDeploymentRequestId(request.getId());
        review.setOrganizationId(request.getOrganizationId());
        review.setPipelineId(request.getPipelineId());
        review.setEnvironmentId(request.getEnvironmentId());
        review.setSubmittedBy(request.getSubmittedBy());
        review.setOutcomeDetail(detail);
        review.setStatus(DeploymentRollbackReviewStatus.PENDING_REVIEW);
        rollbackReviewRepository.save(review);
        return true;
    }

    private void requireActor(DeploymentRequestEntity request, UUID callerId,
                              Set<Permission> callerPermissions) {
        if (request.getSubmittedBy().equals(callerId)
                || (callerPermissions != null && callerPermissions.contains(Permission.QUERY_ADMIN))
                || permissionResolver.resolve(request.getPipelineId(), callerId)
                        .map(EffectiveDeploymentPermission::canTrigger)
                        .orElse(false)) {
            return;
        }
        throw new DeploymentRequestPermissionException(
                "Caller may not act on this deployment request");
    }
}
