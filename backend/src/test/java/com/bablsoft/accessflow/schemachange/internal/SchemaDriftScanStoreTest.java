package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftFindingRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDriftScanStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Mock SchemaDriftScanRepository scanRepository;
    @Mock SchemaDriftConfigRepository configRepository;
    @Mock SchemaDriftFindingRepository findingRepository;

    private SchemaDriftScanStore store;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new SchemaDriftScanStore(scanRepository, configRepository, findingRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(scanRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(configRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private SchemaDriftScanContext ctx() {
        return new SchemaDriftScanContext(orgId, pipelineId, environmentId, datasourceId,
                DbType.POSTGRESQL, SchemaDriftBaseline.PROMOTION_SNAPSHOT, null);
    }

    @Test
    void openStampsEveryColumnFromTheContextAndTheInjectedClock() {
        var scan = store.open(ctx());

        assertThat(scan.getId()).isNotNull();
        assertThat(scan.getOrganizationId()).isEqualTo(orgId);
        assertThat(scan.getPipelineId()).isEqualTo(pipelineId);
        assertThat(scan.getEnvironmentId()).isEqualTo(environmentId);
        assertThat(scan.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(scan.getBaseline()).isEqualTo(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        assertThat(scan.getStartedAt()).isEqualTo(NOW);
        assertThat(scan.getFinishedAt()).isNull();
    }

    @Test
    void finishWritesEveryOutcomeColumnAndCountsTheOwnedFindings() {
        var scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));
        when(findingRepository.countByScan_Id(scan.getId())).thenReturn(7L);

        // Counted from the rows rather than tallied, so the count matches the drill-down at
        // completion even when a reconciliation failed halfway.
        assertThat(store.finish(scan.getId(), false, true, "REASON")).isEqualTo(7);

        assertThat(scan.getFinishedAt()).isEqualTo(NOW);
        assertThat(scan.isApplicable()).isFalse();
        assertThat(scan.isPartial()).isTrue();
        assertThat(scan.getFindingsCount()).isEqualTo(7);
        assertThat(scan.getErrorMessage()).isEqualTo("REASON");
    }

    @Test
    void abandonFinishesTheRowWithoutClaimingItRan() {
        var scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        when(scanRepository.findById(scan.getId())).thenReturn(Optional.of(scan));

        store.abandon(scan.getId(), SchemaDriftScanReason.SCAN_SUPERSEDED);

        assertThat(scan.getFinishedAt()).isEqualTo(NOW);
        assertThat(scan.getErrorMessage()).isEqualTo(SchemaDriftScanReason.SCAN_SUPERSEDED);
        assertThat(scan.getFindingsCount()).isZero();
    }

    @Test
    void aVanishedScanRowIsNotAnError() {
        when(scanRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(store.finish(UUID.randomUUID(), true, false, null)).isZero();
        assertThatCode(() -> store.abandon(UUID.randomUUID(), "X")).doesNotThrowAnyException();
    }

    @Test
    void stampConfigRecordsTheRunAndItsReason() {
        var config = new SchemaDriftConfigEntity();
        config.setId(UUID.randomUUID());
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(config));

        store.stampConfig(pipelineId, orgId, "REASON");

        assertThat(config.getLastScanAt()).isEqualTo(NOW);
        assertThat(config.getLastScanError()).isEqualTo("REASON");
    }

    @Test
    void anUnconfiguredPipelineIsNotStamped() {
        when(configRepository.findByPipelineIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.empty());

        assertThatCode(() -> store.stampConfig(pipelineId, orgId, null)).doesNotThrowAnyException();
    }

    /**
     * The whole reason this class exists: a {@code @Transactional} method a bean calls on itself
     * bypasses the proxy and runs unannotated, and {@code open} in particular must be committed
     * before the executor picks the scan up.
     */
    @Test
    void everyPublicMethodOpensItsOwnTransaction() {
        assertThat(SchemaDriftScanStore.class.getDeclaredMethods())
                .filteredOn(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .isNotEmpty()
                .allSatisfy(method -> {
                    var annotation = method.getAnnotation(Transactional.class);
                    assertThat(annotation)
                            .as("%s must declare its own transaction", method.getName())
                            .isNotNull();
                    assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
                });
        assertThat(Arrays.stream(SchemaDriftScanStore.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("open", "finish", "abandon", "stampConfig");
    }
}
