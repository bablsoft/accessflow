package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAiConfigsControllerTest {

    private AiConfigService aiConfigService;
    private AiAnalyzerStrategy aiAnalyzerStrategy;
    private AuditLogService auditLogService;
    private AdminAiConfigsController controller;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();
    private final RequestAuditContext auditContext =
            new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            new JwtClaims(userId, "admin@example.com", UserRoleType.ADMIN, organizationId),
            "n/a", List.of());

    @BeforeEach
    void setUp() {
        aiConfigService = mock(AiConfigService.class);
        aiAnalyzerStrategy = mock(AiAnalyzerStrategy.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminAiConfigsController(aiConfigService, aiAnalyzerStrategy, auditLogService);
        // The controller calls ServletUriComponentsBuilder.fromCurrentRequest in `create`; bind
        // a fake request to the current thread so URI building works.
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/ai-configs");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void listDelegatesToServiceAndMapsResponses() {
        when(aiConfigService.list(organizationId))
                .thenReturn(List.of(view("Prod", AiProviderType.ANTHROPIC, "claude", false)));
        var list = controller.list(authentication);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("Prod");
    }

    @Test
    void getReturnsSingleConfig() {
        when(aiConfigService.get(configId, organizationId))
                .thenReturn(view("Prod", AiProviderType.ANTHROPIC, "claude", false));
        var response = controller.get(configId, authentication);
        assertThat(response.name()).isEqualTo("Prod");
    }

    @Test
    void testEndpointReturnsErrorWhenAnalyzerThrows() {
        when(aiConfigService.get(configId, organizationId))
                .thenReturn(view("Prod", AiProviderType.ANTHROPIC, "claude", false));
        when(aiAnalyzerStrategy.analyze(anyString(), any(DbType.class), any(),
                anyString(), any(UUID.class)))
                .thenThrow(new RuntimeException("provider unreachable"));

        var response = controller.test(configId, authentication);

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.detail()).contains("provider unreachable");
    }

    @Test
    void updateOmitsAuditWhenNothingChanged() {
        var before = view("Same", AiProviderType.ANTHROPIC, "model", true);
        var after = view("Same", AiProviderType.ANTHROPIC, "model", true);
        when(aiConfigService.get(configId, organizationId)).thenReturn(before);
        when(aiConfigService.update(any(), any(), any())).thenReturn(after);

        var request = new UpdateAiConfigRequest("Same", AiProviderType.ANTHROPIC, "model",
                null, UpdateAiConfigCommand.MASKED_API_KEY, null, null, null);
        controller.update(configId, request, authentication, auditContext);

        verify(auditLogService, never()).record(any(AuditEntry.class));
    }

    @Test
    void updateRecordsProviderChange() {
        var before = view("Same", AiProviderType.ANTHROPIC, "claude", true);
        var after = view("Same", AiProviderType.OPENAI, "claude", true);
        when(aiConfigService.get(configId, organizationId)).thenReturn(before);
        when(aiConfigService.update(any(), any(), any())).thenReturn(after);

        var request = new UpdateAiConfigRequest("Same", AiProviderType.OPENAI, "claude",
                null, UpdateAiConfigCommand.MASKED_API_KEY, null, null, null);
        controller.update(configId, request, authentication, auditContext);

        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void updateRecordsApiKeyRotation() {
        var before = view("Same", AiProviderType.ANTHROPIC, "claude", true);
        var after = view("Same", AiProviderType.ANTHROPIC, "claude", true);
        when(aiConfigService.get(configId, organizationId)).thenReturn(before);
        when(aiConfigService.update(any(), any(), any())).thenReturn(after);

        var request = new UpdateAiConfigRequest("Same", AiProviderType.ANTHROPIC, "claude",
                null, "rotated-key-value", null, null, null);
        controller.update(configId, request, authentication, auditContext);

        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void updateBlankApiKeyOnExistingKeyCountsAsChange() {
        var before = view("Same", AiProviderType.ANTHROPIC, "claude", true);
        var after = view("Same", AiProviderType.ANTHROPIC, "claude", false);
        when(aiConfigService.get(configId, organizationId)).thenReturn(before);
        when(aiConfigService.update(any(), any(), any())).thenReturn(after);

        var request = new UpdateAiConfigRequest("Same", AiProviderType.ANTHROPIC, "claude",
                null, "   ", null, null, null);
        controller.update(configId, request, authentication, auditContext);

        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void updateBlankApiKeyWithNoExistingKeyIsNotARotation() {
        var before = view("Same", AiProviderType.ANTHROPIC, "claude", false);
        var after = view("Same", AiProviderType.ANTHROPIC, "claude", false);
        when(aiConfigService.get(configId, organizationId)).thenReturn(before);
        when(aiConfigService.update(any(), any(), any())).thenReturn(after);

        var request = new UpdateAiConfigRequest("Same", AiProviderType.ANTHROPIC, "claude",
                null, "   ", null, null, null);
        controller.update(configId, request, authentication, auditContext);

        verify(auditLogService, never()).record(any(AuditEntry.class));
    }

    @Test
    void createSwallowsAuditException() {
        var created = view("Prod", AiProviderType.ANTHROPIC, "claude", true);
        when(aiConfigService.create(any(), any())).thenReturn(created);
        when(auditLogService.record(any(AuditEntry.class)))
                .thenThrow(new RuntimeException("audit down"));

        var request = new CreateAiConfigRequest("Prod", AiProviderType.ANTHROPIC, "claude",
                null, "key", null, null, null);
        var response = controller.create(request, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().name()).isEqualTo("Prod");
    }

    @Test
    void deleteRecordsAudit() {
        var response = controller.delete(configId, authentication, auditContext);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(aiConfigService).delete(configId, organizationId);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    private AiConfigView view(String name, AiProviderType provider, String model,
                              boolean apiKeyMasked) {
        return new AiConfigView(configId, organizationId, name, provider, model,
                null, apiKeyMasked, 30000, 4000, 4000, 0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
