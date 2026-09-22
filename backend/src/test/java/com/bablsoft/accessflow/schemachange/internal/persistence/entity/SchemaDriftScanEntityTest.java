package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftScanEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SchemaDriftScanEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var startedAt = Instant.parse("2026-09-01T10:00:00Z");
        var finishedAt = Instant.parse("2026-09-01T10:01:00Z");

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setPipelineId(pipelineId);
        entity.setEnvironmentId(environmentId);
        entity.setDatasourceId(datasourceId);
        entity.setBaseline(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        entity.setStartedAt(startedAt);
        entity.setFinishedAt(finishedAt);
        entity.setApplicable(false);
        entity.setFindingsCount(7);
        entity.setPartial(true);
        entity.setErrorMessage("table cap reached");

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getPipelineId()).isEqualTo(pipelineId);
        assertThat(entity.getEnvironmentId()).isEqualTo(environmentId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getBaseline()).isEqualTo(SchemaDriftBaseline.PROMOTION_SNAPSHOT);
        assertThat(entity.getStartedAt()).isEqualTo(startedAt);
        assertThat(entity.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(entity.isApplicable()).isFalse();
        assertThat(entity.getFindingsCount()).isEqualTo(7);
        assertThat(entity.isPartial()).isTrue();
        assertThat(entity.getErrorMessage()).isEqualTo("table cap reached");
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SchemaDriftScanEntity();

        assertThat(entity.getBaseline()).isNull();
        assertThat(entity.getStartedAt()).isNotNull();
        assertThat(entity.getFinishedAt()).isNull();
        assertThat(entity.isApplicable()).isTrue();
        assertThat(entity.getFindingsCount()).isZero();
        assertThat(entity.isPartial()).isFalse();
        assertThat(entity.getErrorMessage()).isNull();
    }
}
