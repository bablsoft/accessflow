package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeSetStatementEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SchemaChangeSetStatementEntity();
        var id = UUID.randomUUID();
        var changeSet = new SchemaChangeSetEntity();
        var createdAt = Instant.parse("2026-09-01T10:00:00Z");

        entity.setId(id);
        entity.setChangeSet(changeSet);
        entity.setSequenceOrder(2);
        entity.setSqlText("ALTER TABLE orders ADD COLUMN audited_at TIMESTAMPTZ");
        entity.setQueryType(QueryType.DDL);
        entity.setCreatedAt(createdAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getChangeSet()).isSameAs(changeSet);
        assertThat(entity.getSequenceOrder()).isEqualTo(2);
        assertThat(entity.getSqlText()).startsWith("ALTER TABLE");
        assertThat(entity.getQueryType()).isEqualTo(QueryType.DDL);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SchemaChangeSetStatementEntity();

        assertThat(entity.getChangeSet()).isNull();
        assertThat(entity.getSequenceOrder()).isZero();
        assertThat(entity.getSqlText()).isNull();
        assertThat(entity.getQueryType()).isNull();
        assertThat(entity.getCreatedAt()).isNotNull();
    }
}
