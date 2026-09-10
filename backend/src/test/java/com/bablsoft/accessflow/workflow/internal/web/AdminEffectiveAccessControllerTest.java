package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessRow;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessService;
import com.bablsoft.accessflow.workflow.api.InvalidEffectiveAccessQueryException;
import com.bablsoft.accessflow.workflow.api.StatementCapability;
import com.bablsoft.accessflow.workflow.api.TableScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
 * The controller's own branches — audit metadata, the swallow-and-log path, and the three error
 * handlers. The integration test covers the wire contract; these are the cases MockMvc cannot reach.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminEffectiveAccessControllerTest {

    @Mock EffectiveAccessService effectiveAccessService;
    @Mock AuditLogService auditLogService;

    private AdminEffectiveAccessController controller;
    private final StaticMessageSource messages = new StaticMessageSource();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T11:00:00Z"), ZoneOffset.UTC);
    private final UUID callerId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messages.setUseCodeAsDefaultMessage(true);
        controller = new AdminEffectiveAccessController(effectiveAccessService, auditLogService,
                messages, clock);
        when(effectiveAccessService.report(any(), any(), any())).thenReturn(
                new PageResponse<>(List.of(row()), 0, 20, 7, 1));
    }

    @Test
    void theAuditRowRecordsTheQuestionAskedAndTheTotalNotThePageSize() {
        controller.report(datasourceId, "public.payments", StatementCapability.WRITE,
                authentication(), PageRequest.of(0, 20),
                new RequestAuditContext("203.0.113.7", "curl/8.4.0"));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.ACCESS_SIMULATION_RUN);
        assertThat(entry.getValue().resourceId()).isEqualTo(datasourceId);
        assertThat(entry.getValue().metadata())
                .containsEntry("table", "public.payments")
                .containsEntry("capability", "WRITE")
                // 7 matching users across 1 page of 20, not the 1 row this page carries.
                .containsEntry("row_count", 7L);
    }

    @Test
    void aNullAuditContextIsToleratedRatherThanDereferenced() {
        controller.report(datasourceId, "public.payments", StatementCapability.READ,
                authentication(), PageRequest.of(0, 20), null);

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().ipAddress()).isNull();
        assertThat(entry.getValue().userAgent()).isNull();
    }

    @Test
    void anAuditOutageDoesNotDenyTheReport() {
        doThrow(new IllegalStateException("audit sink down")).when(auditLogService).record(any());

        var response = controller.report(datasourceId, "public.payments",
                StatementCapability.READ, authentication(), PageRequest.of(0, 20), null);

        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(7);
    }

    @Test
    void aMissingParameterIsFourHundred() {
        var problem = controller.handleMissingParameter(
                new MissingServletRequestParameterException("table", "String"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    void aTableThatNormalizesAwayIsFourHundred() {
        var problem = controller.handleInvalidQuery(
                new InvalidEffectiveAccessQueryException("empty"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("error", "VALIDATION_ERROR");
    }

    @Test
    void aMistypedParameterIsFourHundred() {
        var problem = controller.handleBadParameter(
                new MethodArgumentTypeMismatchException("nope", String.class, "capability", null,
                        null));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("error", "VALIDATION_ERROR");
    }

    private EffectiveAccessRow row() {
        return new EffectiveAccessRow(UUID.randomUUID(), "dana@example.com", "Dana", "ANALYST",
                true, TableScope.ALL_TABLES, null, false, List.of());
    }

    private UsernamePasswordAuthenticationToken authentication() {
        var claims = new JwtClaims(callerId, "admin@example.com", UserRoleType.ADMIN, null,
                "ADMIN", Set.of(Permission.DATASOURCE_PERMISSION_MANAGE), organizationId, false);
        return new UsernamePasswordAuthenticationToken(claims, null, List.of());
    }
}
