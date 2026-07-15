package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.api.StepUpRequiredException;
import com.bablsoft.accessflow.security.api.StepUpService;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewService.DecisionOutcome;
import com.bablsoft.accessflow.workflow.internal.web.PushDecisionRequest.PushDecisionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit coverage for the one-tap push decide endpoint (AF-444) on {@link ReviewController}. */
class ReviewControllerTest {

    private ReviewService reviewService;
    private ReviewDecisionAuditWriter auditWriter;
    private StepUpService stepUpService;
    private ReviewController controller;

    private final UUID queryId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        reviewService = mock(ReviewService.class);
        auditWriter = mock(ReviewDecisionAuditWriter.class);
        stepUpService = mock(StepUpService.class);
        controller = new ReviewController(reviewService, auditWriter, stepUpService);
        authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(userId, "rev@acme.test", UserRoleType.REVIEWER, orgId, false));
    }

    @Test
    void decideApproveConsumesTokenAndAuditsPushChannel() {
        when(stepUpService.consume("tok")).thenReturn(userId);
        var outcome = new DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED,
                QueryStatus.APPROVED, false);
        when(reviewService.approve(eq(queryId), any(), eq("ok"))).thenReturn(outcome);

        var response = controller.decide(queryId,
                new PushDecisionRequest(PushDecisionType.APPROVE, "ok", "tok"), authentication,
                auditContext);

        assertThat(response.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        verify(reviewService).approve(eq(queryId), any(), eq("ok"));
        verify(auditWriter).record(eq(AuditAction.QUERY_APPROVED), eq(queryId), any(), eq(outcome),
                eq("ok"), eq(auditContext), eq(Map.of("channel", "PUSH", "step_up", true)));
    }

    @Test
    void decideRejectRoutesToReject() {
        when(stepUpService.consume("tok")).thenReturn(userId);
        var outcome = new DecisionOutcome(UUID.randomUUID(), DecisionType.REJECTED,
                QueryStatus.REJECTED, false);
        when(reviewService.reject(eq(queryId), any(), eq("no"))).thenReturn(outcome);

        var response = controller.decide(queryId,
                new PushDecisionRequest(PushDecisionType.REJECT, "no", "tok"), authentication,
                auditContext);

        assertThat(response.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        verify(reviewService).reject(eq(queryId), any(), eq("no"));
        verify(auditWriter).record(eq(AuditAction.QUERY_REJECTED), eq(queryId), any(), eq(outcome),
                eq("no"), eq(auditContext), eq(Map.of("channel", "PUSH", "step_up", true)));
    }

    @Test
    void decideRejectsTokenIssuedToAnotherUser() {
        when(stepUpService.consume("tok")).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> controller.decide(queryId,
                new PushDecisionRequest(PushDecisionType.APPROVE, null, "tok"), authentication,
                auditContext))
                .isInstanceOf(StepUpRequiredException.class);

        verifyNoInteractions(reviewService);
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void decidePropagatesSelfApprovalGuardFromAnyChannel() {
        when(stepUpService.consume("tok")).thenReturn(userId);
        when(reviewService.approve(eq(queryId), any(), any()))
                .thenThrow(new AccessDeniedException("A reviewer cannot review their own query request"));

        assertThatThrownBy(() -> controller.decide(queryId,
                new PushDecisionRequest(PushDecisionType.APPROVE, null, "tok"), authentication,
                auditContext))
                .isInstanceOf(AccessDeniedException.class);

        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
}
