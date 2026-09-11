package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiCallSimulationResult;
import com.bablsoft.accessflow.apigov.api.ApiCallSimulationService;
import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The controller's own branches — audit metadata assembly, the swallow-and-log path, and the error
 * handler. The integration test covers the wire contract; these are the cases MockMvc cannot reach.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminApiCallSimulationControllerTest {

    @Mock ApiCallSimulationService simulationService;
    @Mock AuditLogService auditLogService;

    private AdminApiCallSimulationController controller;
    private final StaticMessageSource messages = new StaticMessageSource();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T11:00:00Z"), ZoneOffset.UTC);
    private final UUID callerId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messages.setUseCodeAsDefaultMessage(true);
        controller = new AdminApiCallSimulationController(simulationService,
                new ApiGovAuditWriter(auditLogService), messages, clock);
        when(simulationService.simulate(any(), any()))
                .thenReturn(result(QueryStatus.PENDING_REVIEW));
    }

    @Test
    void theAuditRowCarriesTheSimulationShapeAndNeverTheCallItself() {
        controller.simulate(request(RiskLevel.HIGH), authentication(),
                new RequestAuditContext("203.0.113.7", "curl/8.4.0"));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        var recorded = entry.getValue();
        assertThat(recorded.action()).isEqualTo(AuditAction.ACCESS_SIMULATION_RUN);
        assertThat(recorded.resourceType()).isEqualTo(AuditResourceType.API_CONNECTOR);
        assertThat(recorded.resourceId()).isEqualTo(connectorId);
        assertThat(recorded.organizationId()).isEqualTo(organizationId);
        assertThat(recorded.actorId()).isEqualTo(callerId);
        assertThat(recorded.ipAddress()).isEqualTo("203.0.113.7");
        assertThat(recorded.metadata())
                .containsEntry("simulated_user_id", userId.toString())
                .containsEntry("ai_outcome", "COMPLETED")
                .containsEntry("risk_level", "HIGH")
                .containsEntry("resulting_status", "PENDING_REVIEW")
                .containsEntry("step_count", 1);
        // API_CONNECTOR_MANAGE does not otherwise grant read access to another user's call content.
        assertThat(recorded.metadata().values()).noneMatch(
                v -> String.valueOf(v).contains("deleteCustomer"));
    }

    @Test
    void optionalMetadataIsOmittedRatherThanNulled() {
        when(simulationService.simulate(any(), any())).thenReturn(result(null));

        controller.simulate(request(null), authentication(),
                new RequestAuditContext(null, null));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().metadata())
                .doesNotContainKey("risk_level")
                .doesNotContainKey("resulting_status")
                .containsEntry("ai_outcome", "SKIPPED");
    }

    @Test
    void anAuditOutageDoesNotDenyTheExplainer() {
        doThrow(new IllegalStateException("audit sink down")).when(auditLogService).record(any());

        var response = controller.simulate(request(RiskLevel.LOW), authentication(),
                new RequestAuditContext(null, null));

        assertThat(response.steps()).hasSize(1);
        assertThat(response.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void anUnreadableBodyIsFourHundred() {
        var problem = controller.handleUnreadableBody(
                new HttpMessageNotReadableException("bad enum", null));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    private SimulateApiCallRequest request(RiskLevel level) {
        return new SimulateApiCallRequest(userId, connectorId, "deleteCustomer", "DELETE",
                level == null ? null : AiOutcome.COMPLETED, level);
    }

    private static ApiCallSimulationResult result(QueryStatus status) {
        return new ApiCallSimulationResult(List.of(DecisionTraceStep.of(
                ApiDecisionStepKind.CONNECTOR_GATES, StepOutcome.ALLOW, "k")), status, List.of());
    }

    private UsernamePasswordAuthenticationToken authentication() {
        var claims = new JwtClaims(callerId, "admin@example.com", UserRoleType.ADMIN, null, "ADMIN",
                Set.of(Permission.API_CONNECTOR_MANAGE), organizationId, false);
        return new UsernamePasswordAuthenticationToken(claims, null, List.of());
    }
}
