package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiReviewService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiReviewControllerTest {

    private ApiReviewService reviewService;
    private AuditLogService auditLogService;
    private ApiReviewController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("1.2.3.4", "ua");

    @BeforeEach
    void setUp() {
        reviewService = mock(ApiReviewService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new ApiReviewController(reviewService, new ApiGovAuditWriter(auditLogService));
    }

    private Authentication auth() {
        var a = mock(Authentication.class);
        when(a.getPrincipal()).thenReturn(new JwtClaims(reviewerId, "r@acme.test", UserRoleType.REVIEWER, orgId));
        return a;
    }

    @Test
    void pendingMapsPageAndForwardsFilters() {
        when(reviewService.listPending(any(), any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        var connectorId = UUID.randomUUID();

        var page = controller.pending(auth(), Pageable.ofSize(20), connectorId, "post");

        assertThat(page.content()).isEmpty();
        var captor = org.mockito.ArgumentCaptor.forClass(
                ApiReviewService.PendingApiReviewFilter.class);
        verify(reviewService).listPending(any(), captor.capture(), any());
        assertThat(captor.getValue().connectorId()).isEqualTo(connectorId);
        assertThat(captor.getValue().verb()).isEqualTo("POST");
    }

    @Test
    void approveDelegatesAndAudits() {
        when(reviewService.approve(eq(requestId), any(), eq("ok"))).thenReturn(
                new ApiReviewService.DecisionOutcome(UUID.randomUUID(), DecisionType.APPROVED, QueryStatus.APPROVED, false));
        var r = controller.approve(requestId, new ApiDecisionRequest("ok"), auth(), auditContext);
        assertThat(r.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        verify(auditLogService).record(argThat(AuditAction.API_REQUEST_APPROVED));
    }

    @Test
    void rejectDelegatesAndAudits() {
        when(reviewService.reject(eq(requestId), any(), eq("no"))).thenReturn(
                new ApiReviewService.DecisionOutcome(UUID.randomUUID(), DecisionType.REJECTED, QueryStatus.REJECTED, false));
        var r = controller.reject(requestId, new ApiDecisionRequest("no"), auth(), auditContext);
        assertThat(r.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        verify(auditLogService).record(argThat(AuditAction.API_REQUEST_REJECTED));
    }

    private static AuditEntry argThat(AuditAction action) {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.action() == action);
    }
}
