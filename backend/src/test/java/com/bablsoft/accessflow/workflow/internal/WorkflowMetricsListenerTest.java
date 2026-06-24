package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import com.bablsoft.accessflow.workflow.events.QueryApprovedEvent;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import com.bablsoft.accessflow.workflow.events.QueryRejectedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowMetricsListenerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final QueryRequestLookupService lookupService = mock(QueryRequestLookupService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-24T12:00:00Z"), ZoneOffset.UTC);
    private final WorkflowMetricsListener listener =
            new WorkflowMetricsListener(meterRegistry, lookupService, clock);

    private final UUID queryId = UUID.randomUUID();

    @Test
    void countsSubmissions() {
        listener.onSubmitted(new QuerySubmittedEvent(queryId));

        assertThat(meterRegistry.get("accessflow.query.submitted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void countsApprovalAndRecordsSlaTimerFromCreatedAt() {
        when(lookupService.findCreatedAt(queryId))
                .thenReturn(Optional.of(clock.instant().minus(Duration.ofSeconds(90))));

        listener.onApproved(new QueryApprovedEvent(queryId, UUID.randomUUID()));

        assertThat(meterRegistry.get("accessflow.query.approved").counter().count()).isEqualTo(1.0);
        var timer = meterRegistry.get("accessflow.query.approval.latency").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(90.0);
    }

    @Test
    void approvalWithoutCreatedAtStillCountsButRecordsNoTimer() {
        when(lookupService.findCreatedAt(queryId)).thenReturn(Optional.empty());

        listener.onApproved(new QueryApprovedEvent(queryId, UUID.randomUUID()));

        assertThat(meterRegistry.get("accessflow.query.approved").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("accessflow.query.approval.latency").timer()).isNull();
    }

    @Test
    void lookupFailureIsSwallowedAndApprovalStillCounted() {
        when(lookupService.findCreatedAt(queryId)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onApproved(new QueryApprovedEvent(queryId, UUID.randomUUID())))
                .doesNotThrowAnyException();
        assertThat(meterRegistry.get("accessflow.query.approved").counter().count()).isEqualTo(1.0);
    }

    @Test
    void rejectionReasonsSplitByTag() {
        listener.onRejected(new QueryRejectedEvent(queryId, UUID.randomUUID()));
        listener.onAutoRejected(new QueryAutoRejectedEvent(queryId, UUID.randomUUID(), "policy"));
        listener.onTimedOut(new QueryTimedOutEvent(queryId, 24));

        assertThat(meterRegistry.get("accessflow.query.rejected").tag("reason", "manual").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("accessflow.query.rejected").tag("reason", "auto").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("accessflow.query.rejected").tag("reason", "timeout").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void executionCountsOutcomeAndRecordsDurationTimer() {
        listener.onExecuted(new QueryExecutedEvent(queryId, 5L, 1_500L, QueryStatus.EXECUTED));
        listener.onExecuted(new QueryExecutedEvent(queryId, null, 200L, QueryStatus.FAILED));

        assertThat(meterRegistry.get("accessflow.query.executed").tag("outcome", "executed").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("accessflow.query.executed").tag("outcome", "failed").counter().count())
                .isEqualTo(1.0);
        var timer = meterRegistry.get("accessflow.query.execution.duration")
                .tag("outcome", "executed").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1_500.0);
    }
}
