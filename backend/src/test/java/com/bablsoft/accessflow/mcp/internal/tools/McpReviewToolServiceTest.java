package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpReviewToolServiceTest {

    @Mock ReviewService reviewService;

    McpReviewToolService tools;
    UUID userId;
    UUID orgId;

    @BeforeEach
    void setUp() {
        tools = new McpReviewToolService(new McpCurrentUser(), reviewService);
        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        authenticateAs(UserRoleType.REVIEWER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_pending_reviews_returns_mapped_page() {
        var pending = new ReviewService.PendingReview(
                UUID.randomUUID(), UUID.randomUUID(), "prod",
                UUID.randomUUID(), "submitter@e.c",
                "SELECT 1", QueryType.SELECT, "demo",
                UUID.randomUUID(), RiskLevel.LOW, 10, "looks ok",
                1, Instant.now());
        when(reviewService.listPendingForReviewer(any(), any()))
                .thenReturn(new PageResponse<>(List.of(pending), 0, 20, 1, 1));

        var result = tools.listPendingReviews(0, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).queryRequestId()).isEqualTo(pending.queryRequestId());
        assertThat(result.items().get(0).aiRiskLevel()).isEqualTo("LOW");
    }

    @Test
    void review_query_routes_each_decision_to_the_right_service_method() {
        var queryId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), eq("ok")))
                .thenReturn(new ReviewService.DecisionOutcome(
                        UUID.randomUUID(), DecisionType.APPROVED, QueryStatus.APPROVED, false));
        when(reviewService.reject(eq(queryId), any(), eq("nope")))
                .thenReturn(new ReviewService.DecisionOutcome(
                        UUID.randomUUID(), DecisionType.REJECTED, QueryStatus.REJECTED, false));
        when(reviewService.requestChanges(eq(queryId), any(), eq("fix")))
                .thenReturn(new ReviewService.DecisionOutcome(
                        UUID.randomUUID(), DecisionType.REQUESTED_CHANGES, QueryStatus.PENDING_REVIEW, false));

        assertThat(tools.reviewQuery(queryId, "approved", "ok").decision()).isEqualTo("APPROVED");
        assertThat(tools.reviewQuery(queryId, "REJECTED", "nope").decision()).isEqualTo("REJECTED");
        assertThat(tools.reviewQuery(queryId, "requested_changes", "fix").decision())
                .isEqualTo("REQUESTED_CHANGES");

        verify(reviewService).approve(eq(queryId), any(), eq("ok"));
        verify(reviewService).reject(eq(queryId), any(), eq("nope"));
        verify(reviewService).requestChanges(eq(queryId), any(), eq("fix"));
    }

    @Test
    void review_query_rejects_unknown_decision() {
        assertThatThrownBy(() -> tools.reviewQuery(UUID.randomUUID(), "MAYBE", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void review_query_rejects_missing_decision() {
        assertThatThrownBy(() -> tools.reviewQuery(UUID.randomUUID(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.reviewQuery(UUID.randomUUID(), "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void review_query_rejects_overlong_comment() {
        var longComment = "x".repeat(4001);
        assertThatThrownBy(() -> tools.reviewQuery(UUID.randomUUID(), "APPROVED", longComment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void self_approval_block_from_service_propagates_as_access_denied() {
        var queryId = UUID.randomUUID();
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenThrow(new AccessDeniedException("A reviewer cannot review their own query request"));
        assertThatThrownBy(() -> tools.reviewQuery(queryId, "APPROVED", null))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void authenticateAs(UserRoleType role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                new JwtClaims(userId, "reviewer@e.c", role, orgId),
                null,
                "ROLE_" + role.name()));
    }
}
