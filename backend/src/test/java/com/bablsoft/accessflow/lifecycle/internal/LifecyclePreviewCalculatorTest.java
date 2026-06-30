package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.proxy.api.QueryDryRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LifecyclePreviewCalculatorTest {

    @Mock
    private QueryDryRunService queryDryRunService;

    private LifecyclePreviewCalculator calculator;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        calculator = new LifecyclePreviewCalculator(queryDryRunService, clock);
    }

    private RetentionPolicyEntity policy(LifecycleAction action, LifecycleTransform transform,
                                         String table) {
        var p = new RetentionPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setOrganizationId(UUID.randomUUID());
        p.setDatasourceId(UUID.randomUUID());
        p.setCreatedBy(UUID.randomUUID());
        p.setTargetTable(table);
        p.setTargetColumns(new String[]{"email"});
        p.setTimestampColumn("created_at");
        p.setRetentionWindow("P30D");
        p.setAction(action);
        p.setTransformType(transform);
        return p;
    }

    @Test
    void preview_returnsEstimateFromDryRun() {
        when(queryDryRunService.dryRun(any(), any(), any(), any(), eq(true))).thenReturn(
                QueryDryRunResult.of("postgresql", QueryType.SELECT, 123L, null, null, Set.of(),
                        Duration.ZERO));

        var result = calculator.preview(policy(LifecycleAction.HARD_DELETE, null, "orders"));

        assertThat(result.totalEstimatedRows()).isEqualTo(123L);
        assertThat(result.tables()).singleElement()
                .satisfies(t -> {
                    assertThat(t.estimatedRows()).isEqualTo(123L);
                    assertThat(t.method()).isEqualTo("HARD_DELETE");
                });
    }

    @Test
    void preview_nullEstimateBecomesUnknown() {
        when(queryDryRunService.dryRun(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(QueryDryRunResult.unsupported("redis"));

        var result = calculator.preview(policy(LifecycleAction.SOFT_DELETE, null, "orders"));

        assertThat(result.totalEstimatedRows()).isZero();
        assertThat(result.tables().get(0).estimatedRows()).isEqualTo(-1L);
        assertThat(result.tables().get(0).method()).isEqualTo("SOFT_DELETE(deleted_at)");
    }

    @Test
    void preview_blankTableSkipsDryRun() {
        var result = calculator.preview(policy(LifecycleAction.PSEUDONYMIZE,
                LifecycleTransform.SHA256_SALTED, null));

        assertThat(result.tables().get(0).estimatedRows()).isEqualTo(-1L);
        assertThat(result.tables().get(0).method()).isEqualTo("PSEUDONYMIZE(SHA256_SALTED)");
    }

    @Test
    void preview_dryRunThrowsIsCaught() {
        when(queryDryRunService.dryRun(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("pool down"));

        var result = calculator.preview(policy(LifecycleAction.HARD_DELETE, null, "orders"));

        assertThat(result.tables().get(0).estimatedRows()).isEqualTo(-1L);
    }
}
