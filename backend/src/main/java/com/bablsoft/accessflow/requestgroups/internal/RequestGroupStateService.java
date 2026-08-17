package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.requestgroups.api.IllegalRequestGroupStateException;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.events.RequestGroupStatusChangedEvent;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;

/**
 * Single chokepoint for request-group status transitions. Validates each transition against the
 * group lifecycle (illegal transitions throw {@link IllegalRequestGroupStateException}), persists the
 * change, and publishes a {@link RequestGroupStatusChangedEvent} so audit / notifications / realtime
 * react from one place.
 */
@Service
@RequiredArgsConstructor
public class RequestGroupStateService {

    private static final Map<RequestGroupStatus, Set<RequestGroupStatus>> LEGAL =
            new EnumMap<>(RequestGroupStatus.class);

    static {
        LEGAL.put(RequestGroupStatus.DRAFT, Set.of(
                RequestGroupStatus.PENDING_AI, RequestGroupStatus.APPROVED, RequestGroupStatus.CANCELLED));
        LEGAL.put(RequestGroupStatus.PENDING_AI, Set.of(
                RequestGroupStatus.PENDING_REVIEW, RequestGroupStatus.APPROVED,
                RequestGroupStatus.REJECTED, RequestGroupStatus.CANCELLED));
        LEGAL.put(RequestGroupStatus.PENDING_REVIEW, Set.of(
                RequestGroupStatus.APPROVED, RequestGroupStatus.REJECTED,
                RequestGroupStatus.TIMED_OUT, RequestGroupStatus.CANCELLED));
        LEGAL.put(RequestGroupStatus.APPROVED, Set.of(
                RequestGroupStatus.EXECUTING, RequestGroupStatus.CANCELLED));
        LEGAL.put(RequestGroupStatus.EXECUTING, Set.of(
                RequestGroupStatus.EXECUTED, RequestGroupStatus.PARTIALLY_EXECUTED,
                RequestGroupStatus.FAILED));
        LEGAL.put(RequestGroupStatus.EXECUTED, Set.of());
        LEGAL.put(RequestGroupStatus.REJECTED, Set.of());
        LEGAL.put(RequestGroupStatus.TIMED_OUT, Set.of());
        LEGAL.put(RequestGroupStatus.PARTIALLY_EXECUTED, Set.of());
        LEGAL.put(RequestGroupStatus.FAILED, Set.of());
        LEGAL.put(RequestGroupStatus.CANCELLED, Set.of());
    }

    private final RequestGroupRepository requestGroupRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Validate, persist, and announce a transition of {@code group} to {@code next}. */
    public RequestGroupEntity apply(RequestGroupEntity group, RequestGroupStatus next) {
        var current = group.getStatus();
        if (current == next) {
            return group;
        }
        if (!LEGAL.getOrDefault(current, Set.of()).contains(next)) {
            throw new IllegalRequestGroupStateException(current,
                    "Illegal request-group transition: " + current + " -> " + next);
        }
        group.setStatus(next);
        var saved = requestGroupRepository.save(group);
        eventPublisher.publishEvent(new RequestGroupStatusChangedEvent(
                saved.getId(), saved.getSubmittedBy(), current, next));
        return saved;
    }

    public static boolean isLegal(RequestGroupStatus from, RequestGroupStatus to) {
        return from == to || LEGAL.getOrDefault(from, Set.of()).contains(to);
    }

    /** Auto-reject a group still in PENDING_REVIEW past its approval timeout. Idempotent. */
    public boolean markTimedOut(UUID groupId) {
        var group = requestGroupRepository.findById(groupId).orElse(null);
        if (group == null || group.getStatus() != RequestGroupStatus.PENDING_REVIEW) {
            return false;
        }
        apply(group, RequestGroupStatus.TIMED_OUT);
        return true;
    }

    /**
     * Stamps {@code escalated_at} (#622). The column is write-only for now — nothing reads it
     * back: there is no group escalation flag in the queue response and no notification. It exists
     * so the idle bundles are already on record when either arrives. Re-checks status and the
     * prior stamp before writing, so a decision racing the scan cannot re-escalate and replicas
     * cannot double-stamp.
     *
     * <p>No event is published: the {@code requestgroups} module has no notification path at all —
     * there is no group notification listener and no group notification context, so grouped
     * requests have never produced a submitted/approved/rejected message either. Publishing an
     * event nothing consumes would be worse than publishing none.
     */
    @Transactional
    public boolean markEscalated(UUID requestGroupId, Instant at) {
        var group = requestGroupRepository.findByIdForUpdate(requestGroupId).orElse(null);
        if (group == null || group.getStatus() != RequestGroupStatus.PENDING_REVIEW
                || group.getEscalatedAt() != null) {
            return false;
        }
        group.setEscalatedAt(at);
        requestGroupRepository.save(group);
        return true;
    }

}
