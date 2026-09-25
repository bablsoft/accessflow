package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetConsumption;
import com.bablsoft.accessflow.core.api.DataBudgetStatus;
import com.bablsoft.accessflow.core.api.DataBudgetStatusService;
import com.bablsoft.accessflow.core.api.DataBudgetUsageRecord;
import com.bablsoft.accessflow.core.api.DataBudgetUsageSource;
import com.bablsoft.accessflow.core.events.DataBudgetThresholdCrossedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetUsageEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultDataBudgetUsageServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    @Mock DataBudgetStatusService statusService;
    @Mock DataBudgetRepository dataBudgetRepository;
    @Mock DataBudgetUsageRepository usageRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private DefaultDataBudgetUsageService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID budgetId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDataBudgetUsageService(statusService, dataBudgetRepository,
                usageRepository, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
        var entity = new DataBudgetEntity();
        entity.setId(budgetId);
        entity.setOrganizationId(orgId);
        when(dataBudgetRepository.findById(budgetId)).thenReturn(Optional.of(entity));
    }

    @Test
    void unbudgetedReadWritesNothing() {
        when(statusService.statusFor(datasourceId, userId))
                .thenReturn(DataBudgetStatus.none(datasourceId));

        service.record(usage(10, 100));

        verify(usageRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void budgetDeletedMidwayWritesNothing() {
        givenStatus(consumption(100L, 0));
        when(dataBudgetRepository.findById(budgetId)).thenReturn(Optional.empty());

        service.record(usage(10, 100));

        verify(usageRepository, never()).save(any());
    }

    @Test
    void recordsTheLedgerRowWithoutEventsBelowTheThreshold() {
        givenStatus(consumption(100L, 10));

        service.record(usage(10, 512));

        verify(usageRepository).lockUserDatasource(
                DefaultDataBudgetUsageService.lockKey(userId, datasourceId));
        var captor = ArgumentCaptor.forClass(DataBudgetUsageEntity.class);
        verify(usageRepository).save(captor.capture());
        var row = captor.getValue();
        assertThat(row.getOrganizationId()).isEqualTo(orgId);
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(row.getRowsRead()).isEqualTo(10);
        assertThat(row.getBytesRead()).isEqualTo(512);
        assertThat(row.getSource()).isEqualTo(DataBudgetUsageSource.QUERY);
        assertThat(row.getQueryRequestId()).isEqualTo(queryId);
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void crossingTheWarnThresholdPublishesOnce() {
        givenStatus(consumption(100L, 70));

        service.record(usage(15, 0));

        var captor = ArgumentCaptor.forClass(DataBudgetThresholdCrossedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.exhausted()).isFalse();
        assertThat(event.usedPercent()).isEqualTo(85);
        assertThat(event.organizationId()).isEqualTo(orgId);
        assertThat(event.budgetId()).isEqualTo(budgetId);
        assertThat(event.usedRows()).isEqualTo(85);
    }

    @Test
    void reachingTheLimitPublishesExhaustedOnly() {
        givenStatus(consumption(100L, 70));

        service.record(usage(200, 0));

        var captor = ArgumentCaptor.forClass(DataBudgetThresholdCrossedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().exhausted()).isTrue();
        assertThat(captor.getValue().usedPercent()).isEqualTo(100);
        assertThat(captor.getValue().breachAction()).isEqualTo(DataBudgetBreachAction.REJECT);
    }

    @Test
    void alreadyExhaustedNeverRepublishes() {
        givenStatus(consumption(100L, 150));

        service.record(usage(10, 0));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void crossingIsStatelessOverBeforeAndAfter() {
        var before = consumption(100L, 79);
        assertThat(DefaultDataBudgetUsageService.crossing(before, before.plus(1, 0))).contains(false);
        assertThat(DefaultDataBudgetUsageService.crossing(before, before.plus(0, 0))).isEmpty();
        var aboveWarn = consumption(100L, 85);
        assertThat(DefaultDataBudgetUsageService.crossing(aboveWarn, aboveWarn.plus(5, 0))).isEmpty();
        var noWarn = new DataBudgetConsumption(budgetId, "b", 100L, null, 60,
                DataBudgetBreachAction.REJECT, null, 10, 0);
        assertThat(DefaultDataBudgetUsageService.crossing(noWarn, noWarn.plus(80, 0))).isEmpty();
    }

    @Test
    void theLockKeyIsStablePerUserAndDatasource() {
        assertThat(DefaultDataBudgetUsageService.lockKey(userId, datasourceId))
                .isEqualTo(DefaultDataBudgetUsageService.lockKey(userId, datasourceId))
                .isNotEqualTo(DefaultDataBudgetUsageService.lockKey(datasourceId, userId));
    }

    private void givenStatus(DataBudgetConsumption consumption) {
        when(statusService.statusFor(datasourceId, userId))
                .thenReturn(new DataBudgetStatus(datasourceId, "warehouse", List.of(consumption)));
    }

    private DataBudgetConsumption consumption(Long maxRows, long usedRows) {
        return new DataBudgetConsumption(budgetId, "Daily", maxRows, null, 1440,
                DataBudgetBreachAction.REJECT, 80, usedRows, 0);
    }

    private DataBudgetUsageRecord usage(long rows, long bytes) {
        return new DataBudgetUsageRecord(userId, datasourceId, rows, bytes,
                DataBudgetUsageSource.QUERY, queryId, null);
    }
}
