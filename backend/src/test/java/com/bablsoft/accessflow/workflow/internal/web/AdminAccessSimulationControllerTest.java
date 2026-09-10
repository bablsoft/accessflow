package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.AccessSimulationResult;
import com.bablsoft.accessflow.workflow.api.AccessSimulationService;
import com.bablsoft.accessflow.workflow.api.AiOutcome;
import com.bablsoft.accessflow.workflow.api.DecisionStepKind;
import com.bablsoft.accessflow.workflow.api.DecisionTraceStep;
import com.bablsoft.accessflow.workflow.api.StepOutcome;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
 * The controller's own branches — audit metadata assembly, the swallow-and-log path, and the two
 * error handlers. The integration test covers the wire contract; these are the cases MockMvc cannot
 * reach (a null {@code RequestAuditContext}, an audit sink that throws).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAccessSimulationControllerTest {

    @Mock AccessSimulationService accessSimulationService;
    @Mock AuditLogService auditLogService;

    private AdminAccessSimulationController controller;
    private final StaticMessageSource messages = new StaticMessageSource();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T11:00:00Z"), ZoneOffset.UTC);
    private final UUID callerId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messages.setUseCodeAsDefaultMessage(true);
        controller = new AdminAccessSimulationController(accessSimulationService, auditLogService,
                messages, clock);
        when(accessSimulationService.simulate(any(), any())).thenReturn(result(
                QueryStatus.PENDING_REVIEW));
    }

    @Test
    void theAuditRowCarriesTheSimulationShapeAndNeverTheSql() {
        controller.simulate(request(RiskLevel.HIGH, 82), authentication(),
                new RequestAuditContext("203.0.113.7", "curl/8.4.0"));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        var recorded = entry.getValue();
        assertThat(recorded.action()).isEqualTo(AuditAction.ACCESS_SIMULATION_RUN);
        assertThat(recorded.resourceId()).isEqualTo(datasourceId);
        assertThat(recorded.organizationId()).isEqualTo(organizationId);
        assertThat(recorded.actorId()).isEqualTo(callerId);
        assertThat(recorded.ipAddress()).isEqualTo("203.0.113.7");
        assertThat(recorded.metadata())
                .containsEntry("simulated_user_id", userId.toString())
                .containsEntry("ai_outcome", "COMPLETED")
                .containsEntry("risk_level", "HIGH")
                .containsEntry("resulting_status", "PENDING_REVIEW")
                .containsEntry("step_count", 1);
        assertThat(recorded.metadata().values()).noneMatch(
                v -> String.valueOf(v).contains("SELECT"));
    }

    @Test
    void optionalMetadataIsOmittedRatherThanNulled() {
        when(accessSimulationService.simulate(any(), any())).thenReturn(result(null));

        controller.simulate(request(null, null), authentication(), null);

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
        doThrow(new IllegalStateException("audit sink down"))
                .when(auditLogService).record(any());

        var response = controller.simulate(request(RiskLevel.LOW, 5), authentication(), null);

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

    @Test
    void aMistypedParameterIsFourHundred() {
        var problem = controller.handleBadParameter(
                new MethodArgumentTypeMismatchException("nope", String.class, "ai_outcome", null,
                        null));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("error", "VALIDATION_ERROR");
    }

    private SimulateAccessRequest request(RiskLevel level, Integer score) {
        return new SimulateAccessRequest(userId, datasourceId, "SELECT 1",
                level == null ? null : AiOutcome.COMPLETED, level, score);
    }

    private AccessSimulationResult result(QueryStatus status) {
        return new AccessSimulationResult(
                List.of(DecisionTraceStep.of(DecisionStepKind.QUOTA, StepOutcome.ALLOW, "k")),
                status, null, List.of());
    }

    private UsernamePasswordAuthenticationToken authentication() {
        var claims = new JwtClaims(callerId, "admin@example.com", UserRoleType.ADMIN, null,
                "ADMIN", Set.of(Permission.DATASOURCE_PERMISSION_MANAGE), organizationId, false);
        return new UsernamePasswordAuthenticationToken(claims, null, List.of());
    }
}
