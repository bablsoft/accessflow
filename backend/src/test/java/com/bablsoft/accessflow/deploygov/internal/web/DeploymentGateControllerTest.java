package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateQueryInvalidException;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateService;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateView;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeploymentGateControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    private DeploymentGateService gateService;
    private DeploymentOutcomeService outcomeService;
    private DeploymentGateController controller;

    @BeforeEach
    void setUp() {
        gateService = mock(DeploymentGateService.class);
        outcomeService = mock(DeploymentOutcomeService.class);
        controller = new DeploymentGateController(gateService, outcomeService);
    }

    @Test
    void gateByTupleDelegatesWithTheCallerContext() {
        when(gateService.gate("payments-api", "production", "2.4.1", orgId, userId, Set.of()))
                .thenReturn(gateView(true));

        var response = controller.gate("payments-api", "2.4.1", "production", null, auth());

        assertThat(response.releasable()).isTrue();
        assertThat(response.approvals().required()).isEqualTo(2);
        assertThat(response.approvals().granted()).isEqualTo(2);
        assertThat(response.aiRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void gateByRequestIdDelegates() {
        when(gateService.gateByRequestId(requestId, orgId, userId, Set.of()))
                .thenReturn(gateView(false));

        var response = controller.gate(null, null, null, requestId, auth());

        assertThat(response.releasable()).isFalse();
        assertThat(response.requestId()).isEqualTo(requestId);
    }

    @Test
    void gateRejectsAPartialTuple() {
        assertThatThrownBy(() -> controller.gate("payments-api", null, "production", null, auth()))
                .isInstanceOf(DeploymentGateQueryInvalidException.class);
        verifyNoInteractions(gateService);
    }

    @Test
    void gateRejectsMixingRequestIdWithTupleParams() {
        assertThatThrownBy(() -> controller.gate("payments-api", "2.4.1", "production", requestId,
                auth()))
                .isInstanceOf(DeploymentGateQueryInvalidException.class);
        verifyNoInteractions(gateService);
    }

    @Test
    void gateRejectsNoParamsAtAll() {
        assertThatThrownBy(() -> controller.gate(null, null, null, null, auth()))
                .isInstanceOf(DeploymentGateQueryInvalidException.class);
        verifyNoInteractions(gateService);
    }

    @Test
    void confirmExecutionDelegatesWithTheAuditIp() {
        when(gateService.confirmExecution(requestId, orgId, userId, Set.of(), "10.0.0.1"))
                .thenReturn(requestView());

        var response = controller.confirmExecution(requestId, auth(),
                new RequestAuditContext("10.0.0.1", "curl"));

        assertThat(response.id()).isEqualTo(requestId);
        verify(gateService).confirmExecution(requestId, orgId, userId, Set.of(), "10.0.0.1");
    }

    @Test
    void reportOutcomeDelegates() {
        when(outcomeService.reportOutcome(requestId, DeploymentOutcome.SUCCEEDED, "green", orgId,
                userId, Set.of(), "10.0.0.1")).thenReturn(requestView());

        var response = controller.reportOutcome(requestId,
                new ReportDeploymentOutcomeRequest(DeploymentOutcome.SUCCEEDED, "green"), auth(),
                new RequestAuditContext("10.0.0.1", "curl"));

        assertThat(response.id()).isEqualTo(requestId);
        verify(outcomeService).reportOutcome(requestId, DeploymentOutcome.SUCCEEDED, "green",
                orgId, userId, Set.of(), "10.0.0.1");
    }

    private Authentication auth() {
        var claims = new JwtClaims(userId, "ci@example.com", UserRoleType.ANALYST,
                UUID.randomUUID(), "ANALYST", Set.<Permission>of(), orgId, false);
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(claims);
        return authentication;
    }

    private DeploymentGateView gateView(boolean releasable) {
        return new DeploymentGateView(requestId, QueryStatus.APPROVED, releasable, 2, 2, List.of(),
                false, null, Instant.parse("2026-08-24T12:00:00Z"), RiskLevel.LOW);
    }

    private DeploymentRequestView requestView() {
        return new DeploymentRequestView(requestId, UUID.randomUUID(), "payments-api", null,
                UUID.randomUUID(), "production", userId, "ci@example.com", "2.4.1", null, null,
                null, null, Map.of(), QueryStatus.EXECUTED, SubmissionReason.USER_SUBMITTED, null,
                null, null, null, null, 1, null, null, null, null,
                Instant.parse("2026-08-24T12:00:00Z"), List.of());
    }
}
