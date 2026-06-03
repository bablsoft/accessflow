package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RowSecurityPolicyEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new RowSecurityPolicyEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var now = Instant.now();

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setTableName("public.orders");
        entity.setColumnName("region");
        entity.setOperator(RowSecurityOperator.IN);
        entity.setValueType(RowSecurityValueType.VARIABLE);
        entity.setValueExpression("user.groups");
        entity.setAppliesToRoles(new String[]{"ANALYST"});
        entity.setAppliesToGroupIds(new UUID[]{groupId});
        entity.setAppliesToUserIds(new UUID[]{userId});
        entity.setEnabled(true);
        entity.setVersion(3L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getTableName()).isEqualTo("public.orders");
        assertThat(entity.getColumnName()).isEqualTo("region");
        assertThat(entity.getOperator()).isEqualTo(RowSecurityOperator.IN);
        assertThat(entity.getValueType()).isEqualTo(RowSecurityValueType.VARIABLE);
        assertThat(entity.getValueExpression()).isEqualTo("user.groups");
        assertThat(entity.getAppliesToRoles()).containsExactly("ANALYST");
        assertThat(entity.getAppliesToGroupIds()).containsExactly(groupId);
        assertThat(entity.getAppliesToUserIds()).containsExactly(userId);
        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getVersion()).isEqualTo(3L);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new RowSecurityPolicyEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }
}
