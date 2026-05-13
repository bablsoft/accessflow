package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.SaveSystemSmtpCommand;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.SystemSmtpView;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.SystemSmtpResponse;
import com.bablsoft.accessflow.security.internal.web.model.TestSystemSmtpRequest;
import com.bablsoft.accessflow.security.internal.web.model.UpdateSystemSmtpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSystemSmtpControllerTest {

    @Mock SystemSmtpService systemSmtpService;
    @Mock AuditLogService auditLogService;

    private AdminSystemSmtpController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("1.2.3.4", "ua/1");

    @BeforeEach
    void setUp() {
        controller = new AdminSystemSmtpController(systemSmtpService, auditLogService);
    }

    private UsernamePasswordAuthenticationToken auth() {
        var claims = new JwtClaims(userId, "admin@example.com", UserRoleType.ADMIN, orgId);
        return new UsernamePasswordAuthenticationToken(claims, "n/a", List.of());
    }

    @Test
    void getReturns404WhenNotConfigured() {
        when(systemSmtpService.findForOrganization(orgId)).thenReturn(Optional.empty());

        var resp = controller.get(auth());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getReturnsMaskedViewWhenConfigured() {
        when(systemSmtpService.findForOrganization(orgId)).thenReturn(Optional.of(
                new SystemSmtpView(orgId, "h", 587, "u", true, "f@x.com", "From", Instant.now())));
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.of(
                new SystemSmtpSendingConfig(orgId, "h", 587, "u", "secret", true, "f@x.com", "From")));

        var resp = controller.get(auth());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().smtpPassword()).isEqualTo(SystemSmtpResponse.MASKED_PASSWORD);
        assertThat(resp.getBody().host()).isEqualTo("h");
    }

    @Test
    void upsertPassesCommandToService() {
        when(systemSmtpService.saveOrUpdate(eq(orgId), any())).thenReturn(
                new SystemSmtpView(orgId, "h", 587, "u", true, "f@x.com", "From", Instant.now()));
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.of(
                new SystemSmtpSendingConfig(orgId, "h", 587, "u", "secret", true, "f@x.com", "From")));

        var resp = controller.upsert(new UpdateSystemSmtpRequest(
                "h", 587, "u", "secret", true, "f@x.com", "From"), auth(), auditContext);

        var captor = ArgumentCaptor.forClass(SaveSystemSmtpCommand.class);
        verify(systemSmtpService).saveOrUpdate(eq(orgId), captor.capture());
        assertThat(captor.getValue().plaintextPassword()).isEqualTo("secret");
        assertThat(resp.smtpPassword()).isEqualTo(SystemSmtpResponse.MASKED_PASSWORD);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void upsertPreservesPasswordWhenMaskedPlaceholderProvided() {
        when(systemSmtpService.saveOrUpdate(eq(orgId), any())).thenReturn(
                new SystemSmtpView(orgId, "h", 587, "u", true, "f@x.com", "From", Instant.now()));
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.of(
                new SystemSmtpSendingConfig(orgId, "h", 587, "u", "secret", true, "f@x.com", "From")));

        controller.upsert(new UpdateSystemSmtpRequest(
                "h", 587, "u", SystemSmtpResponse.MASKED_PASSWORD, true, "f@x.com", "From"),
                auth(), auditContext);

        var captor = ArgumentCaptor.forClass(SaveSystemSmtpCommand.class);
        verify(systemSmtpService).saveOrUpdate(eq(orgId), captor.capture());
        assertThat(captor.getValue().plaintextPassword()).isNull();
    }

    @Test
    void deleteDelegatesToService() {
        var resp = controller.delete(auth(), auditContext);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(systemSmtpService).delete(orgId);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void testWithNullRequestUsesPersistedConfig() {
        var resp = controller.test(null, auth(), auditContext);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(systemSmtpService).sendTest(eq(orgId), eq(null), eq(null));
    }

    @Test
    void testWithOverrideForwardsToService() {
        var request = new TestSystemSmtpRequest("to@example.com",
                "h", 587, "u", "secret", true, "f@x.com", "From");

        controller.test(request, auth(), auditContext);

        var captor = ArgumentCaptor.forClass(SaveSystemSmtpCommand.class);
        verify(systemSmtpService).sendTest(eq(orgId), captor.capture(), eq("to@example.com"));
        assertThat(captor.getValue().host()).isEqualTo("h");
    }

    @Test
    void testSurfacesSystemSmtpNotConfigured() {
        org.mockito.Mockito.doThrow(new SystemSmtpNotConfiguredException())
                .when(systemSmtpService).sendTest(eq(orgId), eq(null), eq(null));

        assertThatThrownBy(() -> controller.test(null, auth(), auditContext))
                .isInstanceOf(SystemSmtpNotConfiguredException.class);
        verify(auditLogService, never()).record(any(AuditEntry.class));
    }
}
