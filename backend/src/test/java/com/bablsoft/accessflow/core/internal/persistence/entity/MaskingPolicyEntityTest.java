package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingPolicyEntityTest {

    @Test
    void gettersReturnSetValues() {
        var entity = new MaskingPolicyEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var now = Instant.now();

        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setColumnRef("public.users.email");
        entity.setStrategy(MaskingStrategy.PARTIAL);
        entity.setStrategyParams("{\"visible_suffix\":\"4\"}");
        entity.setRevealToRoles(new String[]{"ADMIN"});
        entity.setRevealToGroupIds(new UUID[]{groupId});
        entity.setRevealToUserIds(new UUID[]{userId});
        entity.setEnabled(true);
        entity.setVersion(3L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getColumnRef()).isEqualTo("public.users.email");
        assertThat(entity.getStrategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(entity.getStrategyParams()).isEqualTo("{\"visible_suffix\":\"4\"}");
        assertThat(entity.getRevealToRoles()).containsExactly("ADMIN");
        assertThat(entity.getRevealToGroupIds()).containsExactly(groupId);
        assertThat(entity.getRevealToUserIds()).containsExactly(userId);
        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getVersion()).isEqualTo(3L);
        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        var entity = new MaskingPolicyEntity();
        entity.setUpdatedAt(Instant.EPOCH);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    void defaultsStrategyParamsToEmptyObject() {
        assertThat(new MaskingPolicyEntity().getStrategyParams()).isEqualTo("{}");
    }
}
