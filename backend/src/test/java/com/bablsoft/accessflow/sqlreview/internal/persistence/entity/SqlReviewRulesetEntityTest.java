package com.bablsoft.accessflow.sqlreview.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewRulesetEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new SqlReviewRulesetEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-09-01T10:00:00Z");
        var updatedAt = Instant.parse("2026-09-02T10:00:00Z");

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setName("Production");
        entity.setDescription("Strict rules");
        entity.setEnvironment(DatasourceEnvironment.PRODUCTION);
        entity.setEnabled(false);
        entity.setVersion(3L);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getName()).isEqualTo("Production");
        assertThat(entity.getDescription()).isEqualTo("Strict rules");
        assertThat(entity.getEnvironment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.getVersion()).isEqualTo(3L);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void defaultsMatchTheDdl() {
        var entity = new SqlReviewRulesetEntity();

        assertThat(entity.getEnvironment()).isNull();
        assertThat(entity.getDescription()).isNull();
        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getVersion()).isZero();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new SqlReviewRulesetEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
