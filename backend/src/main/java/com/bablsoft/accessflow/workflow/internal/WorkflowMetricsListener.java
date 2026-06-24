package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import com.bablsoft.accessflow.workflow.events.QueryApprovedEvent;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import com.bablsoft.accessflow.workflow.events.QueryRejectedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Translates query-lifecycle domain events into Micrometer meters that back the AccessFlow Grafana
 * dashboards (AF-454): submission / approval / rejection / execution volume, the rejection-rate
 * split by reason, the submission&rarr;approval SLA timer, and the execution-duration timer. Each
 * handler runs {@code AFTER_COMMIT} with {@code fallbackExecution} so the non-transactional
 * {@link QueryExecutedEvent} still fires, and swallows any {@link RuntimeException} so a metrics
 * hiccup never disturbs the workflow.
 */
@Component
@RequiredArgsConstructor
class WorkflowMetricsListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMetricsListener.class);

    private final MeterRegistry meterRegistry;
    private final QueryRequestLookupService queryRequestLookupService;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onSubmitted(QuerySubmittedEvent event) {
        record(() -> meterRegistry.counter("accessflow.query.submitted").increment());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onApproved(QueryApprovedEvent event) {
        record(() -> {
            meterRegistry.counter("accessflow.query.approved").increment();
            queryRequestLookupService.findCreatedAt(event.queryRequestId()).ifPresent(createdAt -> {
                Duration latency = Duration.between(createdAt, clock.instant());
                if (!latency.isNegative()) {
                    Timer.builder("accessflow.query.approval.latency")
                            .register(meterRegistry)
                            .record(latency);
                }
            });
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onRejected(QueryRejectedEvent event) {
        record(() -> meterRegistry.counter("accessflow.query.rejected", "reason", "manual").increment());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAutoRejected(QueryAutoRejectedEvent event) {
        record(() -> meterRegistry.counter("accessflow.query.rejected", "reason", "auto").increment());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onTimedOut(QueryTimedOutEvent event) {
        record(() -> meterRegistry.counter("accessflow.query.rejected", "reason", "timeout").increment());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onExecuted(QueryExecutedEvent event) {
        record(() -> {
            String outcome = event.finalStatus() != null
                    ? event.finalStatus().name().toLowerCase(Locale.ROOT)
                    : "unknown";
            meterRegistry.counter("accessflow.query.executed", "outcome", outcome).increment();
            Timer.builder("accessflow.query.execution.duration")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .record(event.durationMs(), TimeUnit.MILLISECONDS);
        });
    }

    private void record(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.error("Failed to record workflow metric", ex);
        }
    }
}
