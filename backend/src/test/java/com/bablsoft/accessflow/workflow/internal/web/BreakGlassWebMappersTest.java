package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.BreakGlassEligibility;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventView;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
import com.bablsoft.accessflow.workflow.api.BreakGlassService.BreakGlassResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BreakGlassWebMappersTest {

    @Test
    void executeResponseMapsResult() {
        var id = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var response = BreakGlassExecuteResponse.from(
                new BreakGlassResult(id, eventId, QueryStatus.EXECUTED, 5L, 42));
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(response.rowsAffected()).isEqualTo(5L);
        assertThat(response.durationMs()).isEqualTo(42);
    }

    @Test
    void eligibilityResponseMapsList() {
        var dsA = UUID.randomUUID();
        var expiry = Instant.now();
        var response = BreakGlassEligibilityResponse.from(List.of(
                new BreakGlassEligibility(dsA, null),
                new BreakGlassEligibility(UUID.randomUUID(), expiry)));
        assertThat(response.eligibleDatasources()).hasSize(2);
        assertThat(response.eligibleDatasources().get(0).datasourceId()).isEqualTo(dsA);
        assertThat(response.eligibleDatasources().get(1).expiresAt()).isEqualTo(expiry);
    }

    @Test
    void eventResponseMapsView() {
        var view = sampleView();
        var response = BreakGlassEventResponse.from(view);
        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.datasourceName()).isEqualTo("prod-db");
        assertThat(response.submittedByEmail()).isEqualTo("a@x.io");
        assertThat(response.executionStatus()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(response.status()).isEqualTo(BreakGlassStatus.PENDING_REVIEW);
    }

    @Test
    void pageResponseMapsPage() {
        var page = new PageResponse<>(List.of(BreakGlassEventResponse.from(sampleView())),
                0, 20, 1L, 1);
        var response = BreakGlassEventPageResponse.from(page);
        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    private BreakGlassEventView sampleView() {
        return new BreakGlassEventView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "prod-db", UUID.randomUUID(), "Alice", "a@x.io", "SELECT 1",
                QueryStatus.EXECUTED, "prod is down", BreakGlassStatus.PENDING_REVIEW,
                null, null, null, null, Instant.now());
    }
}
