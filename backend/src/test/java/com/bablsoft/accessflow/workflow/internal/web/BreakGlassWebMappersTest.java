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
        var response = BreakGlassEventResponse.from(view, f -> f.ruleId());
        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.datasourceName()).isEqualTo("prod-db");
        assertThat(response.submittedByEmail()).isEqualTo("a@x.io");
        assertThat(response.executionStatus()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(response.status()).isEqualTo(BreakGlassStatus.PENDING_REVIEW);
        assertThat(response.sqlReviewFindings()).isEmpty();
    }

    @Test
    void eventResponseRendersTheSqlReviewFindings() {
        var base = sampleView();
        var finding = new com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding("select_star",
                com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity.BLOCK, 0, 2,
                java.util.Map.of());
        var view = new BreakGlassEventView(base.id(), base.queryRequestId(), null, null,
                base.organizationId(), base.datasourceId(), base.datasourceName(), null, null,
                base.submittedByUserId(), base.submittedByDisplayName(), base.submittedByEmail(),
                base.sqlText(), base.executionStatus(), base.justification(), base.status(),
                null, null, null, null, base.createdAt(), List.of(finding));

        var response = BreakGlassEventResponse.from(view, f -> "rendered:" + f.ruleId());

        assertThat(response.sqlReviewFindings()).hasSize(1);
        assertThat(response.sqlReviewFindings().get(0).ruleId()).isEqualTo("select_star");
        assertThat(response.sqlReviewFindings().get(0).lineNumber()).isEqualTo(2);
        assertThat(response.sqlReviewFindings().get(0).message()).isEqualTo("rendered:select_star");
    }

    @Test
    void pageResponseMapsPage() {
        var page = new PageResponse<>(List.of(BreakGlassEventResponse.from(sampleView(), f -> f.ruleId())),
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
                UUID.randomUUID(), UUID.randomUUID(), null, null, UUID.randomUUID(),
                UUID.randomUUID(), "prod-db", null, null, UUID.randomUUID(), "Alice", "a@x.io",
                "SELECT 1", QueryStatus.EXECUTED, "prod is down", BreakGlassStatus.PENDING_REVIEW,
                null, null, null, null, Instant.now(), List.of());
    }
}
