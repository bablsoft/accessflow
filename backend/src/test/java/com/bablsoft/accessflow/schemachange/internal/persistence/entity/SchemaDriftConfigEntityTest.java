package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftConfigEntityTest {

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SchemaDriftConfigEntity();

        // Drift is opt-in: an upgrade must never start scanning customer databases on a timer.
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.getBaseline()).isEqualTo(SchemaDriftBaseline.PREVIOUS_ENVIRONMENT);
        assertThat(entity.getBaselineEnvironmentId()).isNull();
        assertThat(entity.getScanIntervalHours()).isEqualTo(24);
        assertThat(entity.getLastScanAt()).isNull();
        assertThat(entity.getLastScanError()).isNull();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void gettersReturnSetValues() {
        var entity = new SchemaDriftConfigEntity();
        var id = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var baselineEnvironmentId = UUID.randomUUID();
        var lastScanAt = Instant.parse("2026-09-22T10:00:00Z");

        entity.setId(id);
        entity.setOrganizationId(organizationId);
        entity.setPipelineId(pipelineId);
        entity.setEnabled(true);
        entity.setBaseline(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        entity.setBaselineEnvironmentId(baselineEnvironmentId);
        entity.setScanIntervalHours(6);
        entity.setLastScanAt(lastScanAt);
        entity.setLastScanError("BASELINE_SNAPSHOT_MISSING");

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(organizationId);
        assertThat(entity.getPipelineId()).isEqualTo(pipelineId);
        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getBaseline()).isEqualTo(SchemaDriftBaseline.BASELINE_ENVIRONMENT);
        assertThat(entity.getBaselineEnvironmentId()).isEqualTo(baselineEnvironmentId);
        assertThat(entity.getScanIntervalHours()).isEqualTo(6);
        assertThat(entity.getLastScanAt()).isEqualTo(lastScanAt);
        assertThat(entity.getLastScanError()).isEqualTo("BASELINE_SNAPSHOT_MISSING");
    }

    @Test
    void preUpdateRefreshesTheTimestamp() throws Exception {
        var entity = new SchemaDriftConfigEntity();
        var before = Instant.parse("2020-01-01T00:00:00Z");
        entity.setUpdatedAt(before);

        var onUpdate = SchemaDriftConfigEntity.class.getDeclaredMethod("onUpdate");
        onUpdate.setAccessible(true);
        onUpdate.invoke(entity);

        assertThat(entity.getUpdatedAt()).isAfter(before);
    }
}
