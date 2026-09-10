package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionAggregationService;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionRecomputeInProgressException;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQuerySuggestionRecomputeTriggerTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DATASOURCE = UUID.randomUUID();

    private DatasourceAdminService datasourceAdminService;
    private DistributedLockService distributedLockService;
    private QuerySuggestionAggregationService aggregationService;
    private ExecutorService executor;
    private DefaultQuerySuggestionRecomputeTrigger trigger;

    @BeforeEach
    void setUp() {
        datasourceAdminService = mock(DatasourceAdminService.class);
        distributedLockService = mock(DistributedLockService.class);
        aggregationService = mock(QuerySuggestionAggregationService.class);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        var properties = new QuerySuggestionProperties(true, null, null, 0, 0, 0, 0, 0, null, 1, 1,
                1, 10, 50, Duration.ofMinutes(3));
        trigger = new DefaultQuerySuggestionRecomputeTrigger(datasourceAdminService,
                distributedLockService, aggregationService, properties, executor);
    }

    @Test
    void anUnknownDatasourceIsRejectedBeforeTheLockIsEverTaken() {
        doThrow(new DatasourceNotFoundException(DATASOURCE))
                .when(datasourceAdminService).getForAdmin(DATASOURCE, ORG);

        assertThatThrownBy(() -> trigger.requestRecompute(DATASOURCE, ORG))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(distributedLockService, never()).runLockedAsync(anyString(), any(), any(), any());
    }

    @Test
    void acquiringTheLockHandsTheRebuildToTheExecutor() {
        when(distributedLockService.runLockedAsync(anyString(), eq(Duration.ofMinutes(3)),
                eq(executor), any())).thenReturn(true);

        assertThatCode(() -> trigger.requestRecompute(DATASOURCE, ORG)).doesNotThrowAnyException();

        var captor = ArgumentCaptor.forClass(Runnable.class);
        verify(distributedLockService).runLockedAsync(
                eq("querySuggestionRecompute:" + DATASOURCE), eq(Duration.ofMinutes(3)),
                eq(executor), captor.capture());
        captor.getValue().run();
        verify(aggregationService).aggregateDatasource(ORG, DATASOURCE);
    }

    @Test
    void aLockHeldElsewhereInTheClusterIsReportedAsAConflict() {
        when(distributedLockService.runLockedAsync(anyString(), any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> trigger.requestRecompute(DATASOURCE, ORG))
                .isInstanceOf(QuerySuggestionRecomputeInProgressException.class)
                .extracting(ex -> ((QuerySuggestionRecomputeInProgressException) ex).datasourceId())
                .isEqualTo(DATASOURCE);
        verify(aggregationService, never()).aggregateDatasource(any(), any());
    }
}
