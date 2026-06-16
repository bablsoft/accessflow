package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVersionView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTemplateVersionResponseTest {

    @Test
    void fromCopiesEveryField() {
        var view = new QueryTemplateVersionView(
                UUID.randomUUID(), UUID.randomUUID(), 2, UUID.randomUUID(),
                "Top", "SELECT 1", "desc", List.of("a"), QueryTemplateVisibility.TEAM,
                QueryTemplateChangeType.RESTORED, UUID.randomUUID(), "Alice",
                Instant.parse("2026-01-01T00:00:00Z"));

        var response = QueryTemplateVersionResponse.from(view);

        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.templateId()).isEqualTo(view.templateId());
        assertThat(response.versionNumber()).isEqualTo(2);
        assertThat(response.datasourceId()).isEqualTo(view.datasourceId());
        assertThat(response.name()).isEqualTo("Top");
        assertThat(response.body()).isEqualTo("SELECT 1");
        assertThat(response.description()).isEqualTo("desc");
        assertThat(response.tags()).containsExactly("a");
        assertThat(response.visibility()).isEqualTo(QueryTemplateVisibility.TEAM);
        assertThat(response.changeType()).isEqualTo(QueryTemplateChangeType.RESTORED);
        assertThat(response.authorId()).isEqualTo(view.authorId());
        assertThat(response.authorDisplayName()).isEqualTo("Alice");
        assertThat(response.createdAt()).isEqualTo(view.createdAt());
    }
}
