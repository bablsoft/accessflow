package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestNotFoundException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentStatusChangedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
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
    private final ApplicationEventPublisher eventPublisher;

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
        repository.save(entity);
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
        eventPublisher.publishEvent(new DeploymentDecidedEvent(id, QueryStatus.TIMED_OUT,
                "review_timeout"));
        return true;
    }
}
