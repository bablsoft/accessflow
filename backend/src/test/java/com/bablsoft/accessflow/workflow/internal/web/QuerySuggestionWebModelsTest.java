package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySuggestionWebModelsTest {

    private static final Instant FIRST = Instant.parse("2026-06-01T08:00:00Z");
    private static final Instant LAST = Instant.parse("2026-09-01T08:00:00Z");

    private static QuerySuggestionView view(String sql) {
        return new QuerySuggestionView(UUID.randomUUID(), sql, QueryType.SELECT,
                List.of("public.orders"), 12, 3, FIRST, LAST, 2.5);
    }

    @Test
    void responseCarriesTheEvidenceButNotTheScore() {
        var source = view("select id from orders");

        var response = QuerySuggestionResponse.from(source);

        assertThat(response.id()).isEqualTo(source.id());
        assertThat(response.sql()).isEqualTo("select id from orders");
        assertThat(response.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(response.referencedTables()).containsExactly("public.orders");
        assertThat(response.approvedCount()).isEqualTo(12);
        assertThat(response.distinctSubmitterCount()).isEqualTo(3);
        assertThat(response.firstSubmittedAt()).isEqualTo(FIRST);
        assertThat(response.lastSubmittedAt()).isEqualTo(LAST);
        // The score is a sort key with no meaning to a client; it stays server-side.
        assertThat(QuerySuggestionResponse.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("score"));
    }

    @Test
    void listResponsePreservesRankOrder() {
        var response = QuerySuggestionListResponse.from(List.of(view("first"), view("second")));

        assertThat(response.suggestions()).extracting(QuerySuggestionResponse::sql)
                .containsExactly("first", "second");
    }

    @Test
    void anEmptyRailSerialisesAsAnEmptyListNotNull() {
        assertThat(QuerySuggestionListResponse.from(List.of()).suggestions()).isEmpty();
    }
}
