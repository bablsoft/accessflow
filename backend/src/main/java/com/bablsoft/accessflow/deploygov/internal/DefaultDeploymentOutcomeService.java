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
import com.bablsoft.accessflow.deploygov.api.VerificationStatus;
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
import java.time.Instant;
import java.util.LinkedHashMap;
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
            if (supersedesFailedVerification(request, outcome)) {
                // #1025: the pipeline rolls back a deployment whose verification breached. The
                // request is already FAILED; this records the rollback the breach did not perform.
                request.setOutcome(outcome);
                request.setOutcomeDetail(detail);
                request.setOutcomeReportedAt(clock.instant());
                requestRepository.save(request);
                return recordOutcome(request, outcome, detail, organizationId, callerId, ipAddress,
                        DeploymentOutcome.SUCCEEDED);
            }
            throw new DeploymentOutcomeConflictException(requestId, request.getOutcome(), outcome);
        }
        // Two concurrent FIRST reports of different outcomes both pass the null guard; the loser
        // then fails on the @Version optimistic lock rather than this 409 — an acceptable rare
        // race, since the retry resolves to the idempotent/conflict branch above.

        var reportedAt = clock.instant();
        request.setOutcome(outcome);
        request.setOutcomeDetail(detail);
        request.setOutcomeReportedAt(reportedAt);
        startOrSkipVerification(request, outcome, reportedAt);
        if (outcome == DeploymentOutcome.FAILED) {
            stateService.apply(request, QueryStatus.FAILED);
        } else {
            requestRepository.save(request);
        }
        return recordOutcome(request, outcome, detail, organizationId, callerId, ipAddress, null);
    }

    /**
     * The shared tail of a recorded outcome: version tracker, rollback review, audit row, event.
     * Reached only on a first report or the #1025 carve-out — the idempotent/conflict branches
     * exit earlier.
     */
    private DeploymentRequestView recordOutcome(DeploymentRequestEntity request,
                                                DeploymentOutcome outcome, String detail,
                                                UUID organizationId, UUID callerId,
                                                String ipAddress, DeploymentOutcome superseded) {
        versionTracker.recordOutcome(request, outcome);
        boolean rollbackReviewOpened = outcome == DeploymentOutcome.ROLLED_BACK
                && maybeOpenRollbackReview(request, detail);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "pipeline");
        metadata.put("outcome", outcome.name());
        metadata.put("rollback_review_opened", rollbackReviewOpened);
        if (superseded != null) {
            metadata.put("superseded", superseded.name());
        }
        auditWriter.record(AuditAction.DEPLOYMENT_OUTCOME_REPORTED,
                AuditResourceType.DEPLOYMENT_REQUEST, request.getId(), organizationId, callerId,
                metadata, ipAddress);
        eventPublisher.publishEvent(new DeploymentOutcomeReportedEvent(organizationId,
                request.getId(), request.getPipelineId(), outcome, detail));
        return requestService.detailView(request);
    }

    /** The one accepted second report (#1025): ROLLED_BACK after a breached verification. */
    private static boolean supersedesFailedVerification(DeploymentRequestEntity request,
                                                        DeploymentOutcome outcome) {
        return outcome == DeploymentOutcome.ROLLED_BACK
                && request.getOutcome() == DeploymentOutcome.SUCCEEDED
                && request.getVerificationStatus() == VerificationStatus.FAILED;
    }

    /**
     * #1025: a SUCCEEDED report opens the verification window when the environment configures
     * one, snapshotting the config so a later edit never changes a running window. Verification
     * starts here and never on EXECUTED, which is stamped before the deploy steps run.
     */
    private void startOrSkipVerification(DeploymentRequestEntity request,
                                         DeploymentOutcome outcome, Instant reportedAt) {
        if (outcome != DeploymentOutcome.SUCCEEDED) {
            request.setVerificationStatus(VerificationStatus.SKIPPED);
            request.setVerificationDetail("outcome:" + outcome.name());
            return;
        }
        var config = environmentRepository.findById(request.getEnvironmentId())
                .map(DeploymentEnvironmentEntity::getVerification)
                .filter(json -> !json.isBlank() && !"null".equals(json.strip()))
                .orElse(null);
        if (config == null) {
            request.setVerificationStatus(VerificationStatus.SKIPPED);
            request.setVerificationDetail("no verification configured");
            return;
        }
        request.setVerificationConfig(config);
        request.setVerificationStatus(VerificationStatus.RUNNING);
        request.setVerificationStartedAt(reportedAt);
    }

    /**
     * Open the follow-up review when the environment demands one. The UNIQUE
     * {@code deployment_request_id} column is the concurrency backstop. Reached at most once per
     * request: a first report, or the #1025 carve-out, after which the outcome is ROLLED_BACK.
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
        review.setOnBehalfOfUserId(request.getOnBehalfOfUserId());
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
