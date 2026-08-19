package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportPolicyEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new ExportPolicyEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var now = Instant.now();

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setMode(ExportPolicyMode.ROW_CAP);
        entity.setRowCap(500);
        entity.setDenyClassifications(new String[]{"PII"});
        entity.setAppliesToRoles(new String[]{"ANALYST"});
        entity.setAppliesToGroupIds(new UUID[]{groupId});
        entity.setAppliesToUserIds(new UUID[]{userId});
        entity.setEnabled(false);
        entity.setVersion(3L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getMode()).isEqualTo(ExportPolicyMode.ROW_CAP);
        assertThat(entity.getRowCap()).isEqualTo(500);
        assertThat(entity.getDenyClassifications()).containsExactly("PII");
        assertThat(entity.getAppliesToRoles()).containsExactly("ANALYST");
        assertThat(entity.getAppliesToGroupIds()).containsExactly(groupId);
        assertThat(entity.getAppliesToUserIds()).containsExactly(userId);
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.getVersion()).isEqualTo(3L);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new ExportPolicyEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
