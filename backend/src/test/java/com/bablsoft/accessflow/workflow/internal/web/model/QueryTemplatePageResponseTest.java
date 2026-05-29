package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTemplatePageResponseTest {

    @Test
    void fromCopiesPaginationMetadata() {
        var response = new QueryTemplateResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Alice",
                null, "Top", "SELECT 1", null, List.of(), QueryTemplateVisibility.PRIVATE,
                true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
        var source = new PageResponse<>(List.of(response), 0, 20, 1L, 1);

        var page = QueryTemplatePageResponse.from(source);

        assertThat(page.content()).containsExactly(response);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(1L);
        assertThat(page.totalPages()).isEqualTo(1);
    }
}
