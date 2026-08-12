package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryOccurrenceView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryOccurrenceItemTest {

    @Test
    void fromCopiesAllFields() {
        var id = UUID.randomUUID();
        var executedAt = Instant.parse("2026-08-11T08:00:03Z");
        var createdAt = Instant.parse("2026-08-11T08:00:02Z");
        var view = new QueryOccurrenceView(id, QueryStatus.EXECUTED, 12L, 240,
                executedAt, null, createdAt);

        var item = QueryOccurrenceItem.from(view);

        assertThat(item.id()).isEqualTo(id);
        assertThat(item.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(item.rowsAffected()).isEqualTo(12L);
        assertThat(item.executionDurationMs()).isEqualTo(240);
        assertThat(item.executedAt()).isEqualTo(executedAt);
        assertThat(item.errorMessage()).isNull();
        assertThat(item.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void fromCarriesFailureFields() {
        var view = new QueryOccurrenceView(UUID.randomUUID(), QueryStatus.FAILED, null, null,
                null, "relation \"gone\" does not exist", Instant.parse("2026-08-11T08:00:02Z"));

        var item = QueryOccurrenceItem.from(view);

        assertThat(item.status()).isEqualTo(QueryStatus.FAILED);
        assertThat(item.rowsAffected()).isNull();
        assertThat(item.errorMessage()).contains("does not exist");
    }

    @Test
    void pageResponseCopiesPaginationEnvelope() {
        var item = QueryOccurrenceItem.from(new QueryOccurrenceView(UUID.randomUUID(),
                QueryStatus.EXECUTED, 1L, 5, Instant.now(), null, Instant.now()));
        var page = new PageResponse<>(List.of(item), 0, 20, 41L, 3);

        var response = QueryOccurrencePageResponse.from(page);

        assertThat(response.items()).hasSize(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(41L);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
