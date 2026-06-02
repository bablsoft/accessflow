package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.workflow.api.RoutingAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyEntityTest {

    @Test
    void holdsFieldValues() {
        var entity = new RoutingPolicyEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setName("Block payroll deletes");
        entity.setDescription("desc");
        entity.setPriority(5);
        entity.setEnabled(false);
        entity.setConditionJson("{\"type\":\"query_type\",\"any_of\":[\"DELETE\"]}");
        entity.setAction(RoutingAction.AUTO_REJECT);
        entity.setRequiredApprovals(2);
        entity.setReason("protected");
        entity.setVersion(3L);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getName()).isEqualTo("Block payroll deletes");
        assertThat(entity.getDescription()).isEqualTo("desc");
        assertThat(entity.getPriority()).isEqualTo(5);
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.getConditionJson()).contains("query_type");
        assertThat(entity.getAction()).isEqualTo(RoutingAction.AUTO_REJECT);
        assertThat(entity.getRequiredApprovals()).isEqualTo(2);
        assertThat(entity.getReason()).isEqualTo("protected");
        assertThat(entity.getVersion()).isEqualTo(3L);
        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new RoutingPolicyEntity();
        entity.setUpdatedAt(Instant.parse("2000-01-01T00:00:00Z"));
        entity.onUpdate();
        assertThat(entity.getUpdatedAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
    }
}
