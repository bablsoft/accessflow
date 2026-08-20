package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.config.AuditSinkProperties;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditSinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditSinkDrainServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock AuditSinkRepository sinkRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock AuditSinkDeliverer deliverer;

    private final AuditExportEventWriter eventWriter =
            new AuditExportEventWriter(JsonMapper.builder().build());
    /** batchSize=2, maxBatchesPerTick=3. */
    private final AuditSinkProperties properties =
            new AuditSinkProperties(null, 2, 3);

    private AuditSinkDrainService service;

    @BeforeEach
    void setUp() {
        lenient().when(deliverer.type()).thenReturn(AuditSinkType.HTTPS_BATCH);
        // A Mockito mock stubs the interface's default readyToDeliver to false — restore
        // the streaming-sink behavior unless a test overrides it.
        lenient().when(deliverer.readyToDeliver(any(), anyList(), anyInt(), any()))
                .thenReturn(true);
        // save() must echo the entity: the service adopts the merged instance it returns, and a
        // null here would NPE the multi-batch loop.
        lenient().when(sinkRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new AuditSinkDrainService(sinkRepository, auditLogRepository, eventWriter,
                properties, List.of(deliverer));
    }

    private AuditSinkEntity sink() {
        var sink = new AuditSinkEntity();
        sink.setId(UUID.randomUUID());
        sink.setOrganizationId(ORG_ID);
        sink.setName("sink");
        sink.setType(AuditSinkType.HTTPS_BATCH);
        sink.setConfigJson("{}");
        return sink;
    }

    private static AuditLogEntity row(Instant createdAt) {
        var row = new AuditLogEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(ORG_ID);
        row.setAction("QUERY_SUBMITTED");
        row.setResourceType("query_request");
        row.setMetadata("{}");
        row.setCreatedAt(createdAt);
        return row;
    }

    @Test
    void skipsSinkWhoseNextAttemptIsInTheFuture() {
        var sink = sink();
        sink.setNextAttemptAt(NOW.plusSeconds(10));
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));

        assertThat(service.drainAll(NOW)).isZero();

        verify(auditLogRepository, never()).findAfterKeyset(any(), any(), any(), any());
        verify(deliverer, never()).deliver(any(), anyList());
    }

    @Test
    void drainsBackedOffSinkOnceNextAttemptHasPassed() {
        var sink = sink();
        sink.setNextAttemptAt(NOW.minusSeconds(1));
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of(row(NOW.minusSeconds(60))));

        assertThat(service.drainAll(NOW)).isEqualTo(1);
    }

    @Test
    void drainsMultipleFullBatchesUpToMaxBatchesPerTick() {
        var sink = sink();
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));
        // Always a full batch: the per-tick cap (3) is the only stop.
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenAnswer(inv -> List.of(
                        row(NOW.minusSeconds(60)), row(NOW.minusSeconds(50))));

        assertThat(service.drainAll(NOW)).isEqualTo(1);

        verify(deliverer, times(3)).deliver(any(), anyList());
        verify(sinkRepository, times(3)).save(sink);
    }

    @Test
    void successAdvancesCursorToLastRowAndResetsFailureState() {
        var sink = sink();
        sink.setConsecutiveFailures(2);
        sink.setLastError("old error");
        sink.setNextAttemptAt(NOW.minusSeconds(5));
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));
        var first = row(NOW.minusSeconds(60));
        var last = row(NOW.minusSeconds(50));
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of(first, last))
                .thenReturn(List.of());

        service.drainAll(NOW);

        assertThat(sink.getCursorCreatedAt()).isEqualTo(last.getCreatedAt());
        assertThat(sink.getCursorId()).isEqualTo(last.getId());
        assertThat(sink.getConsecutiveFailures()).isZero();
        assertThat(sink.getNextAttemptAt()).isNull();
        assertThat(sink.getLastSuccessAt()).isEqualTo(NOW);
        assertThat(sink.getLastError()).isNull();
        verify(sinkRepository).save(sink);
    }

    @ParameterizedTest
    @CsvSource({"0, 30", "1, 120", "2, 600", "6, 600"})
    void failureBacksOffPerAttemptAndCapsAtTenMinutes(int priorFailures, long expectedSeconds) {
        var sink = sink();
        sink.setConsecutiveFailures(priorFailures);
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of(row(NOW.minusSeconds(60))));
        doThrow(new AuditSinkDeliveryException("x".repeat(600)))
                .when(deliverer).deliver(any(), anyList());

        assertThat(service.drainAll(NOW)).isZero();

        assertThat(sink.getConsecutiveFailures()).isEqualTo(priorFailures + 1);
        assertThat(sink.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(expectedSeconds));
        assertThat(sink.getLastError()).hasSize(AuditSinkDrainService.LAST_ERROR_MAX_LENGTH);
        assertThat(sink.getCursorCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(sink.getCursorId()).isEqualTo(AuditSinkEntity.CURSOR_ID_FLOOR);
        verify(sinkRepository).save(sink);
    }

    @Test
    void deliveryExceptionWithNullMessageRecordsFallbackError() {
        var sink = sink();
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of(row(NOW.minusSeconds(60))));
        doThrow(new AuditSinkDeliveryException(null)).when(deliverer).deliver(any(), anyList());

        service.drainAll(NOW);

        assertThat(sink.getLastError()).isEqualTo("delivery failed");
    }

    @Test
    void notReadyToDeliverStopsCleanlyWithoutCursorMoveOrError() {
        var sink = sink();
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of(row(NOW.minusSeconds(60))));
        when(deliverer.readyToDeliver(any(), anyList(), anyInt(), any())).thenReturn(false);

        assertThat(service.drainAll(NOW)).isZero();

        verify(deliverer, never()).deliver(any(), anyList());
        verify(sinkRepository, never()).save(any());
        assertThat(sink.getCursorCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(sink.getLastError()).isNull();
        assertThat(sink.getConsecutiveFailures()).isZero();
    }

    @Test
    void oneSinkFailingWithRuntimeExceptionDoesNotStopTheOthers() {
        var failing = sink();
        var healthy = sink();
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc())
                .thenReturn(List.of(failing, healthy));
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of(row(NOW.minusSeconds(60))));
        doThrow(new IllegalStateException("optimistic lock race"))
                .doNothing()
                .when(deliverer).deliver(any(), anyList());

        assertThat(service.drainAll(NOW)).isEqualTo(1);

        assertThat(healthy.getLastSuccessAt()).isEqualTo(NOW);
        assertThat(failing.getLastSuccessAt()).isNull();
        // A non-delivery RuntimeException (corrupt config, decrypt failure) must still land in
        // the backoff/health state — otherwise the sink hot-loops with no admin signal.
        assertThat(failing.getConsecutiveFailures()).isEqualTo(1);
        assertThat(failing.getLastError()).contains("optimistic lock race");
        assertThat(failing.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(30)));
    }

    @Test
    void sinkWithUnregisteredTypeIsSkipped() {
        var sink = sink();
        sink.setType(AuditSinkType.SPLUNK_HEC);
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));

        assertThat(service.drainAll(NOW)).isZero();

        verify(auditLogRepository, never()).findAfterKeyset(any(), any(), any(), any());
    }

    @Test
    void emptyBacklogDeliversNothing() {
        var sink = sink();
        when(sinkRepository.findByEnabledTrueOrderByCreatedAtAsc()).thenReturn(List.of(sink));
        when(auditLogRepository.findAfterKeyset(any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(service.drainAll(NOW)).isZero();

        verify(deliverer, never()).deliver(any(), anyList());
    }

    @Test
    void backoffMapsAttemptsToThirtySecondsTwoMinutesThenTenForever() {
        assertThat(AuditSinkDrainService.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(AuditSinkDrainService.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(AuditSinkDrainService.backoff(3)).isEqualTo(Duration.ofMinutes(10));
        assertThat(AuditSinkDrainService.backoff(7)).isEqualTo(Duration.ofMinutes(10));
        assertThat(AuditSinkDrainService.backoff(0)).isEqualTo(Duration.ofSeconds(30));
    }
}
