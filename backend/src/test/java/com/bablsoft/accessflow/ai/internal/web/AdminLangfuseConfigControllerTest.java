package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.LangfuseConfigService;
import com.bablsoft.accessflow.ai.api.LangfuseConfigView;
import com.bablsoft.accessflow.ai.api.LangfuseConnectionTestResult;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminLangfuseConfigControllerTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            new JwtClaims(userId, "admin@example.com", UserRoleType.ADMIN, organizationId), "n/a", List.of());

    private LangfuseConfigService service;
    private AuditLogService auditLogService;
    private AdminLangfuseConfigController controller;

    @BeforeEach
    void setUp() {
        service = mock(LangfuseConfigService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminLangfuseConfigController(service, auditLogService);
    }

    private LangfuseConfigView view(boolean enabled, boolean secretConfigured) {
        return new LangfuseConfigView(configId, organizationId, enabled, "https://lf.example.com/",
                "pk-1", secretConfigured, true, false, Instant.now(), Instant.now());
    }

    @Test
    void getMapsView() {
        when(service.getOrDefault(organizationId)).thenReturn(view(true, true));
        var response = controller.get(authentication);
        assertThat(response.enabled()).isTrue();
        assertThat(response.secretKey()).isEqualTo("********");
    }

    @Test
    void updateDelegatesAndRecordsAudit() {
        when(service.getOrDefault(organizationId)).thenReturn(view(false, false));
        when(service.update(any(), any())).thenReturn(view(true, true));

        var body = new UpdateLangfuseConfigRequest(true, "https://lf.example.com", "pk-1", "sk-1", true, true);
        var response = controller.update(body, authentication, auditContext);

        assertThat(response.enabled()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.LANGFUSE_CONFIG_UPDATED);
        assertThat(captor.getValue().metadata()).containsEntry("enabled", true);
        assertThat(captor.getValue().metadata()).containsEntry("secret_key_changed", true);
    }

    @Test
    void testDelegatesToService() {
        when(service.testConnection(organizationId))
                .thenReturn(new LangfuseConnectionTestResult(true, "connected"));

        var response = controller.test(authentication);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("connected");
    }
}
