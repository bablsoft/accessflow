package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.CreateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.api.DecisionHookService;
import com.bablsoft.accessflow.workflow.api.DecisionHookTestResult;
import com.bablsoft.accessflow.workflow.api.DecisionHookView;
import com.bablsoft.accessflow.workflow.api.UpdateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.internal.web.model.CreateDecisionHookRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.UpdateDecisionHookRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminDecisionHookControllerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private DecisionHookService service;
    private AuditLogService auditLogService;
    private AdminDecisionHookController controller;
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID hookId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            JwtClaims.forSystemRole(userId, "admin@x.com", UserRoleType.ADMIN, organizationId), "n/a",
            List.of());

    @BeforeEach
    void setUp() {
        service = mock(DecisionHookService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminDecisionHookController(service, auditLogService);
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/decision-hooks");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void listMapsViews() {
        when(service.list(organizationId)).thenReturn(List.of(view()));

        var result = controller.list(authentication);

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("OPA");
            assertThat(r.secretConfigured()).isTrue();
        });
    }

    @Test
    void getMapsTheView() {
        when(service.get(organizationId, hookId)).thenReturn(view());

        assertThat(controller.get(hookId, authentication).id()).isEqualTo(hookId);
    }

    @Test
    void createDefaultsTheOptionalFieldsAndAuditsWithoutTheSecret() {
        when(service.create(any())).thenReturn(view());

        var response = controller.create(new CreateDecisionHookRequest("OPA", null,
                "https://opa.example.com", null, SECRET, null, null), authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getLocation()).hasToString(
                "http://localhost/api/v1/admin/decision-hooks/" + hookId);
        var command = ArgumentCaptor.forClass(CreateDecisionHookCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().timeoutMs()).isEqualTo(2000);
        assertThat(command.getValue().includeSql()).isFalse();
        assertThat(command.getValue().enabled()).isTrue();
        assertThat(command.getValue().organizationId()).isEqualTo(organizationId);
        var entry = audit();
        assertThat(entry.action()).isEqualTo(AuditAction.DECISION_HOOK_CREATED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DECISION_HOOK);
        assertThat(entry.metadata().toString()).doesNotContain(SECRET);
    }

    @Test
    void updatePassesTheSecretThroughAndRecordsARotation() {
        when(service.update(eq(organizationId), eq(hookId), any())).thenReturn(view());

        controller.update(hookId, new UpdateDecisionHookRequest("OPA", null, "https://opa.example.com",
                700, SECRET, true, false), authentication, auditContext);

        var command = ArgumentCaptor.forClass(UpdateDecisionHookCommand.class);
        verify(service).update(eq(organizationId), eq(hookId), command.capture());
        assertThat(command.getValue().secret()).isEqualTo(SECRET);
        assertThat(command.getValue().timeoutMs()).isEqualTo(700);
        assertThat(command.getValue().enabled()).isFalse();
        var entry = audit();
        assertThat(entry.action()).isEqualTo(AuditAction.DECISION_HOOK_UPDATED);
        assertThat(entry.metadata()).containsEntry("secret_rotated", true);
    }

    @Test
    void deleteReturns204AndAudits() {
        var response = controller.delete(hookId, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).delete(organizationId, hookId);
        assertThat(audit().action()).isEqualTo(AuditAction.DECISION_HOOK_DELETED);
    }

    @Test
    void testReportsTheOutcomeAndAuditsIt() {
        when(service.test(organizationId, hookId)).thenReturn(new DecisionHookTestResult(
                DecisionHookOutcome.FAILED, DecisionHookFailure.TIMEOUT, null, null, null, 2001L));

        var response = controller.test(hookId, authentication, auditContext);

        assertThat(response.outcome()).isEqualTo(DecisionHookOutcome.FAILED);
        assertThat(response.failure()).isEqualTo(DecisionHookFailure.TIMEOUT);
        assertThat(response.latencyMs()).isEqualTo(2001L);
        var entry = audit();
        assertThat(entry.action()).isEqualTo(AuditAction.DECISION_HOOK_TESTED);
        assertThat(entry.metadata()).containsEntry("failure", "TIMEOUT");
    }

    @Test
    void anAllowTestAuditsNoFailure() {
        when(service.test(organizationId, hookId)).thenReturn(new DecisionHookTestResult(
                DecisionHookOutcome.ALLOW, null, null, "ok", 200, 3L));

        controller.test(hookId, authentication, auditContext);

        assertThat(audit().metadata()).doesNotContainKey("failure");
    }

    @Test
    void anAuditFailureDoesNotFailTheRequest() {
        doThrow(new IllegalStateException("audit down")).when(auditLogService).record(any());

        var response = controller.delete(hookId, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    private AuditEntry audit() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    private DecisionHookView view() {
        return new DecisionHookView(hookId, organizationId, UUID.randomUUID(), "OPA",
                "https://opa.example.com", 2000, false, true, true, 0L, Instant.now(), Instant.now());
    }
}
