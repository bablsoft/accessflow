package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DataClassification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataClassificationTagEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new DataClassificationTagEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var now = Instant.now();

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setTableName("users");
        entity.setColumnName("email");
        entity.setClassification(DataClassification.PII);
        entity.setNote("pii column");
        entity.setVersion(2L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getTableName()).isEqualTo("users");
        assertThat(entity.getColumnName()).isEqualTo("email");
        assertThat(entity.getClassification()).isEqualTo(DataClassification.PII);
        assertThat(entity.getNote()).isEqualTo("pii column");
        assertThat(entity.getVersion()).isEqualTo(2L);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new DataClassificationTagEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    void columnNameMayBeNullForTableLevelTags() {
        var entity = new DataClassificationTagEntity();
        entity.setColumnName(null);

        assertThat(entity.getColumnName()).isNull();
    }
}
