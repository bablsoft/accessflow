package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeSetPromotionEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SchemaChangeSetPromotionEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var changeSet = new SchemaChangeSetEntity();
        var environmentId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var promotedBy = UUID.randomUUID();
        var submittedAt = Instant.parse("2026-09-01T10:00:00Z");
        var appliedAt = Instant.parse("2026-09-01T10:05:00Z");

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setChangeSet(changeSet);
        entity.setEnvironmentId(environmentId);
        entity.setDatasourceId(datasourceId);
        entity.setRequestGroupId(groupId);
        entity.setStatus(SchemaChangePromotionStatus.APPLIED);
        entity.setStatementsChecksum("0".repeat(64));
        entity.setPromotedBy(promotedBy);
        entity.setSubmittedAt(submittedAt);
        entity.setAppliedAt(appliedAt);
        entity.setErrorMessage("boom");
        entity.setSchemaSnapshot("{\"schemas\":[]}");
        entity.setSnapshotTakenAt(appliedAt);
        entity.setVersion(2L);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getChangeSet()).isSameAs(changeSet);
        assertThat(entity.getEnvironmentId()).isEqualTo(environmentId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getRequestGroupId()).isEqualTo(groupId);
        assertThat(entity.getStatus()).isEqualTo(SchemaChangePromotionStatus.APPLIED);
        assertThat(entity.getStatementsChecksum()).isEqualTo("0".repeat(64));
        assertThat(entity.getPromotedBy()).isEqualTo(promotedBy);
        assertThat(entity.getSubmittedAt()).isEqualTo(submittedAt);
        assertThat(entity.getAppliedAt()).isEqualTo(appliedAt);
        assertThat(entity.getErrorMessage()).isEqualTo("boom");
        assertThat(entity.getSchemaSnapshot()).isEqualTo("{\"schemas\":[]}");
        assertThat(entity.getSnapshotTakenAt()).isEqualTo(appliedAt);
        assertThat(entity.getVersion()).isEqualTo(2L);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SchemaChangeSetPromotionEntity();

        assertThat(entity.getStatus()).isEqualTo(SchemaChangePromotionStatus.PENDING);
        assertThat(entity.getRequestGroupId()).isNull();
        assertThat(entity.getAppliedAt()).isNull();
        assertThat(entity.getErrorMessage()).isNull();
        assertThat(entity.getSchemaSnapshot()).isNull();
        assertThat(entity.getSnapshotTakenAt()).isNull();
        assertThat(entity.getVersion()).isZero();
        assertThat(entity.getSubmittedAt()).isNotNull();
    }
}
