package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftViewMapperTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void mapsEveryScanColumn() {
        var scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        scan.setOrganizationId(UUID.randomUUID());
        scan.setPipelineId(UUID.randomUUID());
        scan.setEnvironmentId(UUID.randomUUID());
        scan.setDatasourceId(UUID.randomUUID());
        scan.setBaseline(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        scan.setStartedAt(NOW);
        scan.setFinishedAt(NOW.plusSeconds(30));
        scan.setApplicable(false);
        scan.setPartial(true);
        scan.setFindingsCount(4);
        scan.setErrorMessage("ENGINE_NOT_APPLICABLE");

        assertThat(SchemaDriftViewMapper.toView(scan)).satisfies(view -> {
            assertThat(view.id()).isEqualTo(scan.getId());
            assertThat(view.organizationId()).isEqualTo(scan.getOrganizationId());
            assertThat(view.pipelineId()).isEqualTo(scan.getPipelineId());
            assertThat(view.environmentId()).isEqualTo(scan.getEnvironmentId());
            assertThat(view.datasourceId()).isEqualTo(scan.getDatasourceId());
            assertThat(view.baseline()).isEqualTo(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
            assertThat(view.startedAt()).isEqualTo(NOW);
            assertThat(view.finishedAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(view.applicable()).isFalse();
            assertThat(view.partial()).isTrue();
            assertThat(view.findingsCount()).isEqualTo(4);
            assertThat(view.errorMessage()).isEqualTo("ENGINE_NOT_APPLICABLE");
        });
    }

    @Test
    void mapsEveryFindingColumnAndFlattensTheOwningScanId() {
        var scan = new SchemaDriftScanEntity();
        scan.setId(UUID.randomUUID());
        var finding = new SchemaDriftFindingEntity();
        finding.setId(UUID.randomUUID());
        finding.setOrganizationId(UUID.randomUUID());
        finding.setEnvironmentId(UUID.randomUUID());
        finding.setScan(scan);
        finding.setObjectPath("public.orders.email");
        finding.setFindingKind(SchemaDriftFindingKind.NULLABILITY_MISMATCH);
        finding.setExpectedValue("NOT NULL");
        finding.setActualValue("NULL");
        finding.setStatus(SchemaDriftFindingStatus.ACKNOWLEDGED);
        finding.setFirstDetectedAt(NOW);
        finding.setLastSeenAt(NOW.plusSeconds(60));
        finding.setResolvedAt(null);

        assertThat(SchemaDriftViewMapper.toView(finding)).satisfies(view -> {
            assertThat(view.id()).isEqualTo(finding.getId());
            assertThat(view.scanId()).isEqualTo(scan.getId());
            assertThat(view.objectPath()).isEqualTo("public.orders.email");
            assertThat(view.findingKind()).isEqualTo(SchemaDriftFindingKind.NULLABILITY_MISMATCH);
            assertThat(view.expectedValue()).isEqualTo("NOT NULL");
            assertThat(view.actualValue()).isEqualTo("NULL");
            assertThat(view.status()).isEqualTo(SchemaDriftFindingStatus.ACKNOWLEDGED);
            assertThat(view.firstDetectedAt()).isEqualTo(NOW);
            assertThat(view.lastSeenAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(view.resolvedAt()).isNull();
        });
    }

    @Test
    void mapsEveryConfigColumn() {
        var config = new SchemaDriftConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(UUID.randomUUID());
        config.setPipelineId(UUID.randomUUID());
        config.setEnabled(true);
        config.setBaseline(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        config.setBaselineEnvironmentId(UUID.randomUUID());
        config.setScanIntervalHours(6);
        config.setLastScanAt(NOW);
        config.setLastScanError("BASELINE_SNAPSHOT_MISSING");

        assertThat(SchemaDriftViewMapper.toView(config)).satisfies(view -> {
            assertThat(view.id()).isEqualTo(config.getId());
            assertThat(view.pipelineId()).isEqualTo(config.getPipelineId());
            assertThat(view.enabled()).isTrue();
            assertThat(view.baseline()).isEqualTo(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
            assertThat(view.baselineEnvironmentId()).isEqualTo(config.getBaselineEnvironmentId());
            assertThat(view.scanIntervalHours()).isEqualTo(6);
            assertThat(view.lastScanAt()).isEqualTo(NOW);
            assertThat(view.lastScanError()).isEqualTo("BASELINE_SNAPSHOT_MISSING");
        });
    }

    @Test
    void theSynthesizedDefaultsMirrorTheMigrationAndSayTheyAreUnconfigured() {
        var organizationId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();

        assertThat(SchemaDriftViewMapper.disabledDefaults(organizationId, pipelineId)).satisfies(view -> {
            assertThat(view.id()).isNull();
            assertThat(view.organizationId()).isEqualTo(organizationId);
            assertThat(view.pipelineId()).isEqualTo(pipelineId);
            assertThat(view.enabled()).isFalse();
            assertThat(view.baseline()).isEqualTo(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
            assertThat(view.baselineEnvironmentId()).isNull();
            assertThat(view.scanIntervalHours()).isEqualTo(24);
            assertThat(view.lastScanAt()).isNull();
            assertThat(view.lastScanError()).isNull();
        });
    }
}
