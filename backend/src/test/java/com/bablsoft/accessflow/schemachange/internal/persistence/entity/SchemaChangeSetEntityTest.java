package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeSetEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SchemaChangeSetEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var pipelineId = UUID.randomUUID();
        var createdBy = UUID.randomUUID();
        var createdAt = Instant.parse("2026-09-01T10:00:00Z");
        var updatedAt = Instant.parse("2026-09-02T10:00:00Z");

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setPipelineId(pipelineId);
        entity.setName("orders-v2");
        entity.setDescription("Adds the audit column");
        entity.setStatus(SchemaChangeSetStatus.ACTIVE);
        entity.setStatementsChecksum("f".repeat(64));
        entity.setCreatedBy(createdBy);
        entity.setVersion(3L);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getPipelineId()).isEqualTo(pipelineId);
        assertThat(entity.getName()).isEqualTo("orders-v2");
        assertThat(entity.getDescription()).isEqualTo("Adds the audit column");
        assertThat(entity.getStatus()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        assertThat(entity.getStatementsChecksum()).isEqualTo("f".repeat(64));
        assertThat(entity.getCreatedBy()).isEqualTo(createdBy);
        assertThat(entity.getVersion()).isEqualTo(3L);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SchemaChangeSetEntity();

        assertThat(entity.getStatus()).isEqualTo(SchemaChangeSetStatus.DRAFT);
        assertThat(entity.getDescription()).isNull();
        assertThat(entity.getStatementsChecksum()).isNull();
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getVersion()).isZero();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new SchemaChangeSetEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
