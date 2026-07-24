package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.PersistQueryEstimateCommand;
import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryEstimatePersistenceService;
import com.bablsoft.accessflow.core.api.QueryEstimateSnapshot;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.events.QueryEstimateCompletedEvent;
import com.bablsoft.accessflow.core.events.QueryEstimateFailedEvent;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryCostEstimateServiceTest {

    @Mock QueryEstimateLookupService lookupService;
    @Mock QueryEstimatePersistenceService persistenceService;
    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock RowSecurityResolutionService rowSecurityResolutionService;
    @Mock QueryExecutor queryExecutor;
    @Mock ApplicationEventPublisher eventPublisher;

    private DefaultQueryCostEstimateService service;

    private final UUID queryRequestId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var properties = new ProxyPoolProperties(null, null, null, null, null, null,
                Duration.ofSeconds(5));
        var messageSource = new StaticMessageSource();
        messageSource.setUseCodeAsDefaultMessage(true);
        service = new DefaultQueryCostEstimateService(lookupService, persistenceService,
                queryRequestLookupService, rowSecurityResolutionService, queryExecutor, properties,
                JsonMapper.builder().build(), eventPublisher, messageSource,
                Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC));
    }

    private QueryRequestSnapshot snapshot(QueryType type, boolean transactional) {
        return new QueryRequestSnapshot(queryRequestId, datasourceId, organizationId, userId,
                "DELETE FROM users WHERE active = false", type, transactional,
                QueryStatus.PENDING_AI, null, null, null, false);
    }

    private QueryEstimateSnapshot persistedSnapshot() {
        return new QueryEstimateSnapshot(estimateId, queryRequestId, "postgresql",
                QueryType.DELETE, true, 100L, 90L, "Seq Scan", 12.5, null, "raw", null,
                false, null, 3, Instant.now());
    }

    @Test
    void returnsExistingEstimateWithoutRecomputing() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.of(persistedSnapshot()));

        var result = service.estimateSubmittedQuery(queryRequestId);

        assertThat(result).hasValueSatisfying(s -> assertThat(s.id()).isEqualTo(estimateId));
        verifyNoInteractions(queryExecutor, persistenceService, eventPublisher);
    }

    @Test
    void emptyWhenQueryRequestMissing() {
        when(lookupService.findByQueryRequestId(queryRequestId)).thenReturn(Optional.empty());
        when(queryRequestLookupService.findById(queryRequestId)).thenReturn(Optional.empty());

        assertThat(service.estimateSubmittedQuery(queryRequestId)).isEmpty();
        verifyNoInteractions(queryExecutor, persistenceService);
    }

    @Test
    void transactionalEnvelopePersistsUnsupportedRow() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.DELETE, true)));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        var result = service.estimateSubmittedQuery(queryRequestId);

        assertThat(result).isPresent();
        var captor = ArgumentCaptor.forClass(PersistQueryEstimateCommand.class);
        verify(persistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().supported()).isFalse();
        assertThat(captor.getValue().unsupportedReason())
                .isEqualTo("error.estimate.transactional_unsupported");
        verify(eventPublisher).publishEvent(any(QueryEstimateCompletedEvent.class));
        verifyNoInteractions(queryExecutor);
    }

    @Test
    void supportedDryRunWithAffectedCountPersistsFullRow() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.DELETE, false)));
        when(rowSecurityResolutionService.resolveApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of());
        var plan = new QueryPlanNode("Seq Scan", "users", 120.0, 44.5, null);
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.of(
                "postgresql", QueryType.DELETE, 120L, plan, "[raw]", Set.of(),
                Duration.ofMillis(12)));
        when(queryExecutor.countAffectedRows(any()))
                .thenReturn(QueryAffectedRowsResult.of("postgresql", 90L, Duration.ofMillis(8)));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        service.estimateSubmittedQuery(queryRequestId);

        var captor = ArgumentCaptor.forClass(PersistQueryEstimateCommand.class);
        verify(persistenceService).persist(eq(queryRequestId), captor.capture());
        var command = captor.getValue();
        assertThat(command.supported()).isTrue();
        assertThat(command.estimatedRows()).isEqualTo(120L);
        assertThat(command.affectedRowCount()).isEqualTo(90L);
        assertThat(command.scanType()).isEqualTo("Seq Scan");
        assertThat(command.estimatedCost()).isEqualTo(44.5);
        assertThat(command.planJson()).contains("\"operation\":\"Seq Scan\"")
                .contains("\"estimated_rows\":120.0");
        verify(eventPublisher).publishEvent(any(QueryEstimateCompletedEvent.class));
        var requestCaptor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).dryRun(requestCaptor.capture());
        assertThat(requestCaptor.getValue().statementTimeoutOverride())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void writePlanDescendsIntoAccessNodeForScanTypeAndEstimate() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.UPDATE, false)));
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        // PostgreSQL-style write plan: root ModifyTable with Plan Rows 0, child Seq Scan.
        var scan = new QueryPlanNode("Seq Scan", "users", 2_400_000.0, 44_543.5, null);
        var root = new QueryPlanNode("ModifyTable", "users", 0.0, 44_543.5, null, List.of(scan));
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.of(
                "postgresql", QueryType.UPDATE, 0L, root, "[raw]", Set.of(), Duration.ZERO));
        when(queryExecutor.countAffectedRows(any()))
                .thenReturn(QueryAffectedRowsResult.unsupported("postgresql"));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        service.estimateSubmittedQuery(queryRequestId);

        var captor = ArgumentCaptor.forClass(PersistQueryEstimateCommand.class);
        verify(persistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().scanType()).isEqualTo("Seq Scan");
        assertThat(captor.getValue().estimatedRows()).isEqualTo(2_400_000L);
    }

    @Test
    void selectSkipsAffectedRowCount() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT, false)));
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.of(
                "postgresql", QueryType.SELECT, 10L, null, null, Set.of(), Duration.ZERO));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        service.estimateSubmittedQuery(queryRequestId);

        verify(queryExecutor, never()).countAffectedRows(any());
    }

    @Test
    void unsupportedDryRunPersistsLocalizedReason() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT, false)));
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.unsupported("redis"));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        service.estimateSubmittedQuery(queryRequestId);

        var captor = ArgumentCaptor.forClass(PersistQueryEstimateCommand.class);
        verify(persistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().supported()).isFalse();
        assertThat(captor.getValue().unsupportedReason()).isEqualTo("error.dry_run.unsupported");
        verify(eventPublisher).publishEvent(any(QueryEstimateCompletedEvent.class));
    }

    @Test
    void unsupportedDryRunKeepsAffectedCountWhenCountWorked() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.UPDATE, false)));
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.unsupported("custom"));
        when(queryExecutor.countAffectedRows(any()))
                .thenReturn(QueryAffectedRowsResult.of("custom", 7L, Duration.ofMillis(4)));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        service.estimateSubmittedQuery(queryRequestId);

        var captor = ArgumentCaptor.forClass(PersistQueryEstimateCommand.class);
        verify(persistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().supported()).isFalse();
        assertThat(captor.getValue().affectedRowCount()).isEqualTo(7L);
    }

    @Test
    void dryRunFailurePersistsFailedSentinelAndPublishesFailedEvent() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.DELETE, false)));
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        when(queryExecutor.dryRun(any())).thenThrow(new IllegalStateException("boom"));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        var result = service.estimateSubmittedQuery(queryRequestId);

        assertThat(result).isPresent();
        var captor = ArgumentCaptor.forClass(PersistQueryEstimateCommand.class);
        verify(persistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().failed()).isTrue();
        assertThat(captor.getValue().errorMessage()).isEqualTo("boom");
        verify(eventPublisher).publishEvent(any(QueryEstimateFailedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(QueryEstimateCompletedEvent.class));
    }

    @Test
    void countFailureLeavesAffectedRowsNull() {
        when(lookupService.findByQueryRequestId(queryRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedSnapshot()));
        when(queryRequestLookupService.findById(queryRequestId))
                .thenReturn(Optional.of(snapshot(QueryType.DELETE, false)));
        when(rowSecurityResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
        when(queryExecutor.dryRun(any())).thenReturn(QueryDryRunResult.of(
                "postgresql", QueryType.DELETE, 120L, null, null, Set.of(), Duration.ZERO));
        lenient().when(queryExecutor.countAffectedRows(any()))
                .thenThrow(new IllegalStateException("count failed"));
        when(persistenceService.persist(eq(queryRequestId), any())).thenReturn(estimateId);

        service.estimateSubmittedQuery(queryRequestId);

        var captor = ArgumentCaptor.forClass(PersistQueryEstimateCommand.class);
        verify(persistenceService).persist(eq(queryRequestId), captor.capture());
        assertThat(captor.getValue().supported()).isTrue();
        assertThat(captor.getValue().affectedRowCount()).isNull();
        verify(eventPublisher).publishEvent(any(QueryEstimateCompletedEvent.class));
    }
}
