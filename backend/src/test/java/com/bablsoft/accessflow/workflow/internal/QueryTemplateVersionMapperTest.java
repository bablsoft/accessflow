package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTemplateVersionMapperTest {

    @Test
    void toViewCopiesAllFieldsAndAuthorName() {
        var entity = newEntity();
        entity.setTags(new String[]{"a", "b"});
        entity.setDatasourceId(UUID.randomUUID());

        var view = QueryTemplateVersionMapper.toView(entity, "Alice");

        assertThat(view.id()).isEqualTo(entity.getId());
        assertThat(view.templateId()).isEqualTo(entity.getTemplateId());
        assertThat(view.versionNumber()).isEqualTo(3);
        assertThat(view.datasourceId()).isEqualTo(entity.getDatasourceId());
        assertThat(view.name()).isEqualTo(entity.getName());
        assertThat(view.body()).isEqualTo(entity.getBody());
        assertThat(view.description()).isEqualTo(entity.getDescription());
        assertThat(view.tags()).containsExactly("a", "b");
        assertThat(view.visibility()).isEqualTo(QueryTemplateVisibility.TEAM);
        assertThat(view.changeType()).isEqualTo(QueryTemplateChangeType.UPDATED);
        assertThat(view.authorId()).isEqualTo(entity.getAuthorId());
        assertThat(view.authorDisplayName()).isEqualTo("Alice");
        assertThat(view.createdAt()).isEqualTo(entity.getCreatedAt());
    }

    @Test
    void toViewMapsNullTagsToEmptyListAndNullAuthor() {
        var entity = newEntity();
        entity.setTags(null);
        entity.setDatasourceId(null);

        var view = QueryTemplateVersionMapper.toView(entity, null);

        assertThat(view.tags()).isEmpty();
        assertThat(view.authorDisplayName()).isNull();
        assertThat(view.datasourceId()).isNull();
    }

    private QueryTemplateVersionEntity newEntity() {
        var entity = new QueryTemplateVersionEntity();
        entity.setId(UUID.randomUUID());
        entity.setTemplateId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setVersionNumber(3);
        entity.setName("Top");
        entity.setBody("SELECT 1");
        entity.setDescription("desc");
        entity.setVisibility(QueryTemplateVisibility.TEAM);
        entity.setChangeType(QueryTemplateChangeType.UPDATED);
        entity.setAuthorId(UUID.randomUUID());
        entity.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return entity;
    }
}
