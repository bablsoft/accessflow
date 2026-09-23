package com.bablsoft.accessflow.core.internal.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RowLimitPolicyEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new RowLimitPolicyEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var now = Instant.now();

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setSchemaName("crm");
        entity.setTableName("customer");
        entity.setMaxRows(250);
        entity.setAppliesToRoles(new String[]{"ANALYST"});
        entity.setAppliesToGroupIds(new UUID[]{groupId});
        entity.setAppliesToUserIds(new UUID[]{userId});
        entity.setEnabled(false);
        entity.setVersion(2L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getSchemaName()).isEqualTo("crm");
        assertThat(entity.getTableName()).isEqualTo("customer");
        assertThat(entity.getMaxRows()).isEqualTo(250);
        assertThat(entity.getAppliesToRoles()).containsExactly("ANALYST");
        assertThat(entity.getAppliesToGroupIds()).containsExactly(groupId);
        assertThat(entity.getAppliesToUserIds()).containsExactly(userId);
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.getVersion()).isEqualTo(2L);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new RowLimitPolicyEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
