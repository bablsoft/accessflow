package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.config.AuditSinkProperties;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditSinkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drains new {@code audit_log} rows to every enabled external sink (#628), called from
 * {@link com.bablsoft.accessflow.audit.internal.scheduled.AuditSinkDrainJob}.
 *
 * <p>Delivery is at-least-once by construction: the sink's keyset cursor advances only after a
 * successful delivery, so a crash between delivery and cursor commit re-sends the batch
 * (receivers dedupe on the immutable event {@code id}). The keyset cannot skip rows because
 * per-organization audit writes serialize on a {@code pg_advisory_xact_lock} — no committed row
 * can later appear behind the cursor. Do not weaken that write-path serialization without
 * revisiting this coupling.
 *
 * <p>Failures never propagate across sinks: each failing sink records its error, backs off
 * (30s → 2m → 10m, then capped at 10m forever — the durable cursor makes retry-forever safe),
 * and is skipped until {@code next_attempt_at}. A concurrent admin update losing an optimistic
 * lock race against the drain is logged and retried next tick.
 */
@Service
@Slf4j
public class AuditSinkDrainService {

    private static final List<Duration> BACKOFF =
            List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
    static final int LAST_ERROR_MAX_LENGTH = 500;

    private final AuditSinkRepository sinkRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditExportEventWriter eventWriter;
    private final AuditSinkProperties properties;
    private final Map<AuditSinkType, AuditSinkDeliverer> deliverers;

    public AuditSinkDrainService(AuditSinkRepository sinkRepository,
                                 AuditLogRepository auditLogRepository,
                                 AuditExportEventWriter eventWriter,
                                 AuditSinkProperties properties,
                                 List<AuditSinkDeliverer> delivererList) {
        this.sinkRepository = sinkRepository;
        this.auditLogRepository = auditLogRepository;
        this.eventWriter = eventWriter;
        this.properties = properties;
        this.deliverers = delivererList.stream().collect(Collectors.toMap(
                AuditSinkDeliverer::type, Function.identity(),
                (a, b) -> a, () -> new EnumMap<>(AuditSinkType.class)));
    }

    /** Returns the number of sinks that received at least one batch this tick. */
    public int drainAll(Instant now) {
        var sinks = sinkRepository.findByEnabledTrueOrderByCreatedAtAsc();
        int delivered = 0;
        for (var sink : sinks) {
            if (sink.getNextAttemptAt() != null && sink.getNextAttemptAt().isAfter(now)) {
                continue;
            }
            try {
                if (drainSink(sink, now) > 0) {
                    delivered++;
                }
            } catch (RuntimeException ex) {
                // Deliberate per-sink isolation: also catches optimistic-lock races with a
                // concurrent admin update; the next tick re-reads the row.
                log.error("Audit sink drain failed for sink {} ({})", sink.getId(),
                        sink.getType(), ex);
            }
        }
        return delivered;
    }

    private int drainSink(AuditSinkEntity sink, Instant now) {
        var deliverer = deliverers.get(sink.getType());
        if (deliverer == null) {
            log.error("No deliverer registered for audit sink type {}", sink.getType());
            return 0;
        }
        int batches = 0;
        for (int i = 0; i < properties.maxBatchesPerTick(); i++) {
            var rows = auditLogRepository.findAfterKeyset(
                    sink.getOrganizationId(), sink.getCursorCreatedAt(), sink.getCursorId(),
                    PageRequest.of(0, properties.batchSize()));
            if (rows.isEmpty()) {
                break;
            }
            AuditExportEvent last;
            try {
                var batch = rows.stream().map(eventWriter::toEvent).toList();
                if (!deliverer.readyToDeliver(sink, batch, properties.batchSize(), now)) {
                    break;
                }
                deliverer.deliver(sink, batch);
                last = batch.getLast();
            } catch (RuntimeException ex) {
                // Not just AuditSinkDeliveryException: a corrupt or undecryptable config throws
                // from the codec inside the deliverer, and without recording it here the sink
                // would hot-loop every tick with no backoff and no health signal.
                recordFailure(sink, now, ex);
                return batches;
            }
            sink = recordSuccess(sink, now, last);
            batches++;
            if (rows.size() < properties.batchSize()) {
                break;
            }
        }
        return batches;
    }

    // Both record methods return the saved (merged) instance and the caller must adopt it: the
    // loaded entity is detached and @Version-locked, so re-saving the original after a prior
    // save would merge a stale version and throw on every multi-batch tick.
    private AuditSinkEntity recordSuccess(AuditSinkEntity sink, Instant now,
                                          AuditExportEvent lastDelivered) {
        sink.setCursorCreatedAt(lastDelivered.createdAt());
        sink.setCursorId(lastDelivered.id());
        sink.setConsecutiveFailures(0);
        sink.setNextAttemptAt(null);
        sink.setLastSuccessAt(now);
        sink.setLastError(null);
        return sinkRepository.save(sink);
    }

    private AuditSinkEntity recordFailure(AuditSinkEntity sink, Instant now, RuntimeException ex) {
        int failures = sink.getConsecutiveFailures() + 1;
        sink.setConsecutiveFailures(failures);
        sink.setNextAttemptAt(now.plus(backoff(failures)));
        sink.setLastError(truncate(ex.getMessage()));
        var saved = sinkRepository.save(sink);
        log.warn("Audit sink {} ({}) delivery failed (attempt {}), retrying at {}: {}",
                sink.getId(), sink.getType(), failures, sink.getNextAttemptAt(), ex.getMessage());
        return saved;
    }

    static Duration backoff(int consecutiveFailures) {
        int index = Math.min(consecutiveFailures, BACKOFF.size()) - 1;
        return BACKOFF.get(Math.max(index, 0));
    }

    private static String truncate(String message) {
        if (message == null) {
            return "delivery failed";
        }
        return message.length() <= LAST_ERROR_MAX_LENGTH
                ? message : message.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
