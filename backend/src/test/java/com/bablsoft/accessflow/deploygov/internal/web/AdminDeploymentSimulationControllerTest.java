package com.bablsoft.accessflow.deploygov.internal.web;

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
import com.bablsoft.accessflow.deploygov.api.DeploymentDecisionStepKind;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationResult;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationService;
import com.bablsoft.accessflow.deploygov.internal.DeploygovAuditWriter;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDeploymentSimulationControllerTest {

    private static final Instant AT = Instant.parse("2026-09-11T18:30:00Z");

    @Mock DeploymentSimulationService simulationService;
    @Mock AuditLogService auditLogService;

    private AdminDeploymentSimulationController controller;
    private final StaticMessageSource messages = new StaticMessageSource();
    private final Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
    private final UUID callerId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messages.setUseCodeAsDefaultMessage(true);
        controller = new AdminDeploymentSimulationController(simulationService,
                new DeploygovAuditWriter(auditLogService), messages, clock);
        when(simulationService.simulate(any(), any()))
                .thenReturn(result(QueryStatus.PENDING_REVIEW, false));
    }

    @Test
    void theAuditRowNamesThePipelineTheEnvironmentAndTheEvaluatedInstant() {
        controller.simulate(request(RiskLevel.HIGH), authentication(),
                new RequestAuditContext("203.0.113.7", "curl/8.4.0"));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        var recorded = entry.getValue();
        assertThat(recorded.action()).isEqualTo(AuditAction.ACCESS_SIMULATION_RUN);
        assertThat(recorded.resourceType()).isEqualTo(AuditResourceType.DEPLOYMENT_PIPELINE);
        assertThat(recorded.resourceId()).isEqualTo(pipelineId);
        assertThat(recorded.actorId()).isEqualTo(callerId);
        assertThat(recorded.ipAddress()).isEqualTo("203.0.113.7");
        assertThat(recorded.userAgent()).isEqualTo("curl/8.4.0");
        assertThat(recorded.metadata())
                .containsEntry("simulated_user_id", userId.toString())
                .containsEntry("environment_id", environmentId.toString())
                .containsEntry("ai_outcome", "COMPLETED")
                .containsEntry("risk_level", "HIGH")
                .containsEntry("resulting_status", "PENDING_REVIEW")
                .containsEntry("releasable", false)
                .containsEntry("evaluated_at", AT.toString())
                .containsEntry("step_count", 1);
    }

    @Test
    void optionalMetadataIsOmittedRatherThanNulledAndANullContextIsTolerated() {
        when(simulationService.simulate(any(), any())).thenReturn(result(null, false));

        controller.simulate(request(null), authentication(), null);

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().metadata())
                .doesNotContainKey("risk_level")
                .doesNotContainKey("resulting_status");
        assertThat(entry.getValue().ipAddress()).isNull();
        assertThat(entry.getValue().userAgent()).isNull();
    }

    @Test
    void anAuditOutageDoesNotDenyTheExplainer() {
        doThrow(new IllegalStateException("audit sink down")).when(auditLogService).record(any());

        var response = controller.simulate(request(RiskLevel.LOW), authentication(), null);

        assertThat(response.steps()).hasSize(1);
        assertThat(response.releasable()).isFalse();
    }

    @Test
    void anUnreadableBodyIsFourHundred() {
        var problem = controller.handleUnreadableBody(
                new HttpMessageNotReadableException("bad instant", null));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    private SimulateDeploymentRequest request(RiskLevel level) {
        return new SimulateDeploymentRequest(userId, pipelineId, environmentId, "2.6.0",
                level == null ? null : AiOutcome.COMPLETED, level, null, null);
    }

    private static DeploymentSimulationResult result(QueryStatus status, boolean releasable) {
        return new DeploymentSimulationResult(List.of(DecisionTraceStep.of(
                DeploymentDecisionStepKind.PIPELINE_GATES, StepOutcome.ALLOW, "k")), status,
                releasable, AT, List.of());
    }

    private UsernamePasswordAuthenticationToken authentication() {
        var claims = new JwtClaims(callerId, "admin@example.com", UserRoleType.ADMIN, null, "ADMIN",
                Set.of(Permission.DEPLOYMENT_PIPELINE_MANAGE), organizationId, false);
        return new UsernamePasswordAuthenticationToken(claims, null, List.of());
    }
}
