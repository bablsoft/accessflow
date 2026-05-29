package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTemplateMapperTest {

    @Test
    void toViewCopiesAllFieldsAndOwnerName() {
        var entity = newEntity();
        entity.setTags(new String[]{"a", "b"});
        entity.setDatasourceId(UUID.randomUUID());

        var view = QueryTemplateMapper.toView(entity, "Alice");

        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.organizationId()).isEqualTo(entity.getOrganizationId());
        assertThat(view.ownerId()).isEqualTo(entity.getOwnerId());
        assertThat(view.ownerDisplayName()).isEqualTo("Alice");
        assertThat(view.datasourceId()).isEqualTo(entity.getDatasourceId());
        assertThat(view.name()).isEqualTo(entity.getName());
        assertThat(view.body()).isEqualTo(entity.getBody());
        assertThat(view.description()).isEqualTo(entity.getDescription());
        assertThat(view.tags()).containsExactly("a", "b");
        assertThat(view.visibility()).isEqualTo(QueryTemplateVisibility.PRIVATE);
        assertThat(view.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(view.updatedAt()).isEqualTo(entity.getUpdatedAt());
    }

    @Test
    void toViewMapsNullTagsToEmptyList() {
        var entity = newEntity();
        entity.setTags(null);

        var view = QueryTemplateMapper.toView(entity, null);

        assertThat(view.tags()).isEmpty();
        assertThat(view.ownerDisplayName()).isNull();
        assertThat(view.datasourceId()).isNull();
    }

    private QueryTemplateEntity newEntity() {
        var entity = new QueryTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setOwnerId(UUID.randomUUID());
        entity.setName("Top");
        entity.setBody("SELECT 1");
        entity.setDescription("desc");
        entity.setVisibility(QueryTemplateVisibility.PRIVATE);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return entity;
    }
}
