package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentReviewControllerTest {

    private DeploymentReviewService reviewService;
    private DeploymentReviewController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reviewService = mock(DeploymentReviewService.class);
        controller = new DeploymentReviewController(reviewService);
    }

    private Authentication auth() {
        var a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(reviewerId, "r@acme.test", UserRoleType.REVIEWER, orgId));
        return a;
    }

    private DeploymentReviewService.PendingDeploymentReview pendingView(UUID pipelineId) {
        return new DeploymentReviewService.PendingDeploymentReview(requestId, pipelineId,
                "api-deploy", UUID.randomUUID(), "production", UUID.randomUUID(), "2.4.1",
                "abc123", "https://ci.acme.test/run/1", "hotfix", UUID.randomUUID(),
                RiskLevel.HIGH, 82, "Risky deploy", 1, 2, null, Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void pendingForwardsFilterAndPageableAndMapsThePage() {
        var pipelineId = UUID.randomUUID();
        when(reviewService.listPending(any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(pendingView(pipelineId)), 0, 20, 1, 1));

        var page = controller.pending(auth(), Pageable.ofSize(20), pipelineId);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).deploymentRequestId()).isEqualTo(requestId);
        assertThat(page.content().get(0).pipelineName()).isEqualTo("api-deploy");
        assertThat(page.content().get(0).aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(page.totalElements()).isEqualTo(1);
        var contextCaptor = ArgumentCaptor.forClass(DeploymentReviewService.ReviewerContext.class);
        var filterCaptor = ArgumentCaptor.forClass(
                DeploymentReviewService.PendingDeploymentReviewFilter.class);
        var pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(reviewService).listPending(contextCaptor.capture(), filterCaptor.capture(),
                pageCaptor.capture());
        assertThat(filterCaptor.getValue().pipelineId()).isEqualTo(pipelineId);
        assertThat(pageCaptor.getValue().page()).isZero();
        assertThat(pageCaptor.getValue().size()).isEqualTo(20);
        assertThat(contextCaptor.getValue().userId()).isEqualTo(reviewerId);
        assertThat(contextCaptor.getValue().organizationId()).isEqualTo(orgId);
    }

    @Test
    void approveDelegatesWithTheCallerContextAndMapsTheOutcome() {
        var decisionId = UUID.randomUUID();
        when(reviewService.approve(eq(requestId), any(), eq("ok"))).thenReturn(
                new DeploymentReviewService.DecisionOutcome(decisionId, DecisionType.APPROVED,
                        QueryStatus.APPROVED, false));

        var r = controller.approve(requestId, new DeploymentDecisionRequest("ok"), auth());

        assertThat(r.decisionId()).isEqualTo(decisionId);
        assertThat(r.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(r.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(r.duplicate()).isFalse();
        var contextCaptor = ArgumentCaptor.forClass(DeploymentReviewService.ReviewerContext.class);
        verify(reviewService).approve(eq(requestId), contextCaptor.capture(), eq("ok"));
        assertThat(contextCaptor.getValue().userId()).isEqualTo(reviewerId);
        assertThat(contextCaptor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(contextCaptor.getValue().roleName()).isEqualTo("REVIEWER");
        assertThat(contextCaptor.getValue().permissions())
                .contains(Permission.DEPLOYMENT_REVIEW);
    }

    @Test
    void rejectDelegatesAndMapsAnIdempotentReplay() {
        var decisionId = UUID.randomUUID();
        when(reviewService.reject(eq(requestId), any(), eq("no"))).thenReturn(
                new DeploymentReviewService.DecisionOutcome(decisionId, DecisionType.REJECTED,
                        QueryStatus.REJECTED, true));

        var r = controller.reject(requestId, new DeploymentDecisionRequest("no"), auth());

        assertThat(r.decisionId()).isEqualTo(decisionId);
        assertThat(r.decision()).isEqualTo(DecisionType.REJECTED);
        assertThat(r.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        assertThat(r.duplicate()).isTrue();
    }
}
