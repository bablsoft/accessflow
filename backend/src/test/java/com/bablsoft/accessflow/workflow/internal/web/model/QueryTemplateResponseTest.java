package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.QueryTemplateView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTemplateResponseTest {

    @Test
    void fromMarksEditableTrueWhenCallerIsOwner() {
        var ownerId = UUID.randomUUID();
        var view = view(ownerId);

        var response = QueryTemplateResponse.from(view, ownerId);

        assertThat(response.editable()).isTrue();
        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.tags()).containsExactly("a");
        assertThat(response.visibility()).isEqualTo(QueryTemplateVisibility.TEAM);
    }

    @Test
    void fromMarksEditableFalseForNonOwner() {
        var view = view(UUID.randomUUID());

        var response = QueryTemplateResponse.from(view, UUID.randomUUID());

        assertThat(response.editable()).isFalse();
    }

    @Test
    void fromMarksEditableFalseWhenOwnerIdIsNull() {
        var view = new QueryTemplateView(
                UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                "X", "SELECT 1", null, List.of(), QueryTemplateVisibility.PRIVATE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));

        var response = QueryTemplateResponse.from(view, UUID.randomUUID());

        assertThat(response.editable()).isFalse();
    }

    private QueryTemplateView view(UUID ownerId) {
        return new QueryTemplateView(
                UUID.randomUUID(), UUID.randomUUID(), ownerId, "Alice",
                UUID.randomUUID(), "Top", "SELECT 1", "desc",
                List.of("a"), QueryTemplateVisibility.TEAM,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
    }
}
