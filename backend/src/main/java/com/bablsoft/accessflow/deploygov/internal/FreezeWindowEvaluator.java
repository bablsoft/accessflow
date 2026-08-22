package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a deployment freeze is in effect for a (pipeline, environment) at a point in
 * time (#688, epic #682). A window matches a request when its scope columns are null or equal
 * (both null = org-wide); among simultaneously active windows the most-specific scope wins
 * (environment > pipeline > org-wide), then {@code REJECT} over {@code HOLD}, then the oldest.
 *
 * <p>Recurring windows evaluate wall-clock in their configured IANA timezone; an {@code endTime}
 * before {@code startTime} spans midnight into the following day. <strong>Fail closed:</strong> a
 * window whose stored definition cannot be evaluated (an invalid zone id, a day number outside
 * 1–7, an incomplete shape) counts as an active {@code HOLD} — never {@code REJECT}, so a broken
 * definition can hold requests but can never destroy them.
 */
@Service
@RequiredArgsConstructor
class FreezeWindowEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FreezeWindowEvaluator.class);

    private final DeploymentFreezeWindowRepository freezeWindowRepository;
    private final Clock clock;

    /** The freeze in effect right now, or empty when no enabled window is active. */
    @Transactional(readOnly = true)
    Optional<ActiveFreeze> evaluate(UUID organizationId, UUID pipelineId, UUID environmentId) {
        return evaluate(organizationId, pipelineId, environmentId, clock.instant());
    }

    /** The freeze in effect at {@code at} — used by the scheduled-deploy path (#692). */
    @Transactional(readOnly = true)
    Optional<ActiveFreeze> evaluate(UUID organizationId, UUID pipelineId, UUID environmentId,
                                    Instant at) {
        return freezeWindowRepository.findByOrganizationIdAndEnabledTrue(organizationId).stream()
                .filter(w -> matchesScope(w, pipelineId, environmentId))
                .map(w -> toActiveFreeze(w, at))
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(ActiveFreeze::specificity).reversed()
                        .thenComparing(f -> f.behavior() == FreezeBehavior.REJECT ? 0 : 1)
                        .thenComparing(ActiveFreeze::createdAt)
                        .thenComparing(f -> f.windowId().toString()));
    }

    private static boolean matchesScope(DeploymentFreezeWindowEntity w, UUID pipelineId,
                                        UUID environmentId) {
        var pipelineMatches = w.getPipelineId() == null || w.getPipelineId().equals(pipelineId);
        var environmentMatches = w.getEnvironmentId() == null
                || w.getEnvironmentId().equals(environmentId);
        return pipelineMatches && environmentMatches;
    }

    private static Optional<ActiveFreeze> toActiveFreeze(DeploymentFreezeWindowEntity w, Instant at) {
        FreezeBehavior behavior;
        try {
            if (!isEvaluable(w)) {
                log.warn("Freeze window {} has an inconsistent shape; failing closed as HOLD",
                        w.getId());
                behavior = FreezeBehavior.HOLD;
            } else if (isActiveAt(w, at)) {
                behavior = w.getBehavior();
            } else {
                return Optional.empty();
            }
        } catch (DateTimeException ex) {
            log.warn("Freeze window {} could not be evaluated ({}); failing closed as HOLD",
                    w.getId(), ex.getMessage());
            behavior = FreezeBehavior.HOLD;
        }
        return Optional.of(new ActiveFreeze(w.getId(), behavior, w.getReason(), specificityOf(w),
                w.getCreatedAt()));
    }

    /** Exactly one shape must be complete — mirrors {@code chk_deployment_freeze_window_shape}. */
    private static boolean isEvaluable(DeploymentFreezeWindowEntity w) {
        var oneOff = w.getStartsAt() != null && w.getEndsAt() != null;
        var recurring = w.getDaysOfWeek() != null && w.getDaysOfWeek().length > 0
                && w.getStartTime() != null && w.getEndTime() != null && w.getTimezone() != null;
        return oneOff ^ recurring;
    }

    private static boolean isActiveAt(DeploymentFreezeWindowEntity w, Instant at) {
        if (w.getStartsAt() != null) {
            return !at.isBefore(w.getStartsAt()) && at.isBefore(w.getEndsAt());
        }
        var zoned = at.atZone(ZoneId.of(w.getTimezone()));
        var time = zoned.toLocalTime();
        var day = zoned.getDayOfWeek().getValue();
        var previousDay = zoned.getDayOfWeek().minus(1).getValue();
        var start = w.getStartTime();
        var end = w.getEndTime();
        if (start.isBefore(end)) {
            return isListedDay(w, day) && !time.isBefore(start) && time.isBefore(end);
        }
        // endTime < startTime spans midnight: the day-of-week membership applies to the day the
        // window starts, so the early-morning tail belongs to the previous day's window.
        return (isListedDay(w, day) && !time.isBefore(start))
                || (isListedDay(w, previousDay) && time.isBefore(end));
    }

    private static boolean isListedDay(DeploymentFreezeWindowEntity w, int isoDay) {
        for (var d : w.getDaysOfWeek()) {
            if (d < 1 || d > 7) {
                throw new DateTimeException("day of week out of range: " + d);
            }
            if (d == isoDay) {
                return true;
            }
        }
        return false;
    }

    private static int specificityOf(DeploymentFreezeWindowEntity w) {
        if (w.getEnvironmentId() != null) {
            return 2;
        }
        return w.getPipelineId() != null ? 1 : 0;
    }

    /** A freeze in effect: the winning window, its behavior, and the admin-supplied reason. */
    record ActiveFreeze(UUID windowId, FreezeBehavior behavior, String reason, int specificity,
                        Instant createdAt) {
    }
}
