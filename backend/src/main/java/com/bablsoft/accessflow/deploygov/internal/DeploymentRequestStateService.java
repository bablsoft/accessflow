package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.api.VerificationStatus;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentReviewEscalatedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentReviewNudgedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentStatusChangedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentVerificationFailedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single chokepoint for deployment-request status transitions: validates the transition,
 * persists, and publishes {@link DeploymentStatusChangedEvent}. Callers load the entity inside their
 * own transaction and pass it here so the version-checked save participates in that transaction.
 *
 * <p>Unlike the API-governance sibling, this class <em>enforces</em> the state machine: anything
 * outside {@link #ALLOWED} throws {@link IllegalDeploymentRequestStateException}. Re-applying the
 * status a row already has is a silent no-op, so a redelivered event never turns into a 409.
 *
 * <p>Some legal transitions are unreachable in #691 and exist for the sub-issues that follow:
 * {@code PENDING_REVIEW → APPROVED/REJECTED/TIMED_OUT} is the review flow (#692), and
 * {@code APPROVED → EXECUTED/FAILED/TIMED_OUT} is the gate plus outcome reporting (#693), which
 * also adds the one post-terminal flip {@code EXECUTED → FAILED} for a {@code FAILED} outcome
 * report. {@code PENDING_AI → CANCELLED} is likewise legal here but unreachable through the API — the
 * cancel endpoint rejects {@code PENDING_AI} — so a future "cancel a stuck analysis" admin path
 * costs nothing.
 */
@Service
@RequiredArgsConstructor
public class DeploymentRequestStateService {

    private static final Map<QueryStatus, Set<QueryStatus>> ALLOWED = allowedTransitions();

    private final DeploymentRequestRepository repository;
    private final DeploygovAuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final DeploymentVersionTrackerService versionTracker;
    private final Clock clock;

    private static Map<QueryStatus, Set<QueryStatus>> allowedTransitions() {
        var allowed = new EnumMap<QueryStatus, Set<QueryStatus>>(QueryStatus.class);
        allowed.put(QueryStatus.PENDING_AI, EnumSet.of(QueryStatus.PENDING_REVIEW,
                QueryStatus.APPROVED, QueryStatus.REJECTED, QueryStatus.CANCELLED));
        allowed.put(QueryStatus.PENDING_REVIEW, EnumSet.of(QueryStatus.APPROVED,
                QueryStatus.REJECTED, QueryStatus.TIMED_OUT, QueryStatus.CANCELLED));
        allowed.put(QueryStatus.APPROVED, EnumSet.of(QueryStatus.EXECUTED, QueryStatus.FAILED,
                QueryStatus.TIMED_OUT, QueryStatus.CANCELLED));
        // The one post-terminal flip: a FAILED outcome report moves an executed deployment to
        // FAILED (#693).
        allowed.put(QueryStatus.EXECUTED, EnumSet.of(QueryStatus.FAILED));
        for (var terminal : EnumSet.of(QueryStatus.REJECTED, QueryStatus.TIMED_OUT,
                QueryStatus.FAILED, QueryStatus.CANCELLED)) {
            allowed.put(terminal, EnumSet.noneOf(QueryStatus.class));
        }
        return Map.copyOf(allowed);
    }

    public DeploymentRequestEntity require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new DeploymentRequestNotFoundException(id));
    }

    /**
     * Apply {@code next}, persist, and publish the status-changed event.
     *
     * @throws IllegalDeploymentRequestStateException the transition is not part of the state machine
     */
    public void apply(DeploymentRequestEntity entity, QueryStatus next) {
        var previous = entity.getStatus();
        if (previous == next) {
            return;
        }
        if (!ALLOWED.getOrDefault(previous, Set.of()).contains(next)) {
            throw new IllegalDeploymentRequestStateException(previous, next);
        }
        entity.setStatus(next);
        if (next == QueryStatus.EXECUTED) {
            // #742: the drift math's time axis — same transaction and clock as the tracker's
            // deployed_at. The same-status early return above keeps a redelivered confirmation
            // from restamping or double-shifting.
            entity.setExecutedAt(clock.instant());
        }
        if (next == QueryStatus.PENDING_REVIEW) {
            // #984: the approval-latency axis. PENDING_REVIEW is only reachable from PENDING_AI,
            // and the same-status early return keeps a redelivery from restamping.
            entity.setReviewStartedAt(clock.instant());
        }
        repository.save(entity);
        if (next == QueryStatus.EXECUTED) {
            // #741: maintain the per-environment deployed-version projection in this transaction.
            // EXECUTED is only reachable from APPROVED.
            versionTracker.recordExecution(entity);
        }
        eventPublisher.publishEvent(new DeploymentStatusChangedEvent(
                entity.getId(), entity.getSubmittedBy(), previous, next));
    }

    /**
     * Auto-reject one request past its review timeout (#692), called per row by
     * {@code DeploymentTimeoutJob}. Transactional here so the decided event is published inside a
     * real transaction (transactional listeners would otherwise silently skip it). Returns false
     * when the row was decided between the scan and this call — not an error, just a lost race.
     */
    @Transactional
    public boolean markTimedOut(UUID id) {
        var entity = require(id);
        if (entity.getStatus() != QueryStatus.PENDING_REVIEW) {
            return false;
        }
        apply(entity, QueryStatus.TIMED_OUT);
        // #695: system decision — null actor, trigger names the mechanism.
        auditWriter.record(AuditAction.DEPLOYMENT_TIMED_OUT, AuditResourceType.DEPLOYMENT_REQUEST,
                entity.getId(), entity.getOrganizationId(), null,
                Map.of("trigger", "timeout", "pipeline_id", entity.getPipelineId().toString(),
                        "version", entity.getVersion()),
                null);
        eventPublisher.publishEvent(new DeploymentDecidedEvent(id, QueryStatus.TIMED_OUT,
                "review_timeout"));
        return true;
    }

    /**
     * Stamps {@code escalated_at} (#985), called per row by {@code DeploymentReviewEscalationJob}.
     * Re-checks status and the prior stamp under a row lock, so a decision racing the scan produces
     * no stray notification and a sibling replica cannot double-fire. Notify-only: eligibility, the
     * self-approval rule and the decision path never read the stamp.
     */
    @Transactional
    public boolean markEscalated(UUID id, Instant at) {
        var entity = repository.findByIdForUpdate(id).orElse(null);
        if (entity == null || entity.getStatus() != QueryStatus.PENDING_REVIEW
                || entity.getEscalatedAt() != null) {
            return false;
        }
        entity.setEscalatedAt(at);
        repository.save(entity);
        recordReminder(AuditAction.DEPLOYMENT_REVIEW_ESCALATED, entity, "escalation");
        eventPublisher.publishEvent(new DeploymentReviewEscalatedEvent(id));
        return true;
    }

    /** Stamps {@code last_nudged_at} (#985); a no-op once the request has left review. */
    @Transactional
    public boolean markNudged(UUID id, Instant at) {
        var entity = repository.findByIdForUpdate(id).orElse(null);
        if (entity == null || entity.getStatus() != QueryStatus.PENDING_REVIEW) {
            return false;
        }
        entity.setLastNudgedAt(at);
        repository.save(entity);
        recordReminder(AuditAction.DEPLOYMENT_REVIEW_NUDGED, entity, "nudge");
        eventPublisher.publishEvent(new DeploymentReviewNudgedEvent(id));
        return true;
    }

    /**
     * Fails a running post-deployment verification (#1025), called by the verification service.
     * The breach is the plain {@code EXECUTED → FAILED} flip and nothing more: {@code outcome} stays
     * {@code SUCCEEDED}, the version tracker is not reverted (nobody rolled back) and no
     * outcome-reported event is published. Returns false when the row is no longer a running
     * verification — a lost race, not an error.
     */
    @Transactional
    public boolean markVerificationFailed(UUID id, String check, String detail) {
        var entity = require(id);
        if (!isRunningVerification(entity)) {
            return false;
        }
        entity.setVerificationStatus(VerificationStatus.FAILED);
        entity.setVerificationFinishedAt(clock.instant());
        entity.setVerificationDetail(detail);
        apply(entity, QueryStatus.FAILED);
        // #1026: system row — null actor; check is null when the config snapshot was unreadable.
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "verification");
        metadata.put("pipeline_id", entity.getPipelineId().toString());
        metadata.put("version", entity.getVersion());
        if (check != null) {
            metadata.put("check", check);
        }
        metadata.put("detail", detail);
        auditWriter.record(AuditAction.DEPLOYMENT_VERIFICATION_FAILED,
                AuditResourceType.DEPLOYMENT_REQUEST, entity.getId(), entity.getOrganizationId(),
                null, metadata, null);
        eventPublisher.publishEvent(new DeploymentVerificationFailedEvent(
                entity.getOrganizationId(), entity.getId(), entity.getPipelineId(), detail));
        return true;
    }

    /** Ends a running verification as {@code PASSED} after a clean window (#1025); no event. */
    @Transactional
    public boolean markVerificationPassed(UUID id) {
        var entity = require(id);
        if (!isRunningVerification(entity)) {
            return false;
        }
        entity.setVerificationStatus(VerificationStatus.PASSED);
        entity.setVerificationFinishedAt(clock.instant());
        repository.save(entity);
        return true;
    }

    private static boolean isRunningVerification(DeploymentRequestEntity entity) {
        return entity.getStatus() == QueryStatus.EXECUTED
                && entity.getVerificationStatus() == VerificationStatus.RUNNING;
    }

    private void recordReminder(AuditAction action, DeploymentRequestEntity entity, String trigger) {
        auditWriter.record(action, AuditResourceType.DEPLOYMENT_REQUEST, entity.getId(),
                entity.getOrganizationId(), null,
                Map.of("trigger", trigger, "pipeline_id", entity.getPipelineId().toString()),
                null);
    }
}
