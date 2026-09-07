package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpAgentConfigService;
import com.bablsoft.accessflow.ai.api.HelpAgentConfigView;
import com.bablsoft.accessflow.ai.api.HelpAgentConnectionTestResult;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

class AdminHelpAgentConfigControllerTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();
    private final UUID aiConfigId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.9", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            JwtClaims.forSystemRole(userId, "admin@example.com", UserRoleType.ADMIN, organizationId),
            "n/a", List.of());

    private HelpAgentConfigService service;
    private AuditLogService auditLogService;
    private AdminHelpAgentConfigController controller;

    @BeforeEach
    void setUp() {
        service = mock(HelpAgentConfigService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminHelpAgentConfigController(service, auditLogService);
    }

    @Test
    void getMapsTheView() {
        when(service.getOrDefault(organizationId)).thenReturn(view(true, aiConfigId, true, 90));

        var response = controller.get(authentication);

        assertThat(response.id()).isEqualTo(configId);
        assertThat(response.enabled()).isTrue();
        assertThat(response.aiConfigId()).isEqualTo(aiConfigId);
        assertThat(response.topK()).isEqualTo(6);
        assertThat(response.indexedCorpusVersion()).isEqualTo("c0ac599ef7fc");
    }

    @Test
    void updateDelegatesAndAuditsOnlyWhatChanged() {
        when(service.getOrDefault(organizationId)).thenReturn(view(false, null, true, 90));
        when(service.update(any(), any())).thenReturn(view(true, aiConfigId, false, 30));

        var body = new UpdateHelpAgentConfigRequest(true, aiConfigId, null, false, 6, 0.4, 8, 2000,
                true, 30, 6);
        var response = controller.update(body, authentication, auditContext);

        assertThat(response.enabled()).isTrue();
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.HELP_AGENT_CONFIG_UPDATED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.HELP_AGENT_CONFIG);
        assertThat(entry.resourceId()).isEqualTo(configId);
        assertThat(entry.metadata())
                .containsEntry("enabled", true)
                .containsEntry("ai_config_bound", true)
                .containsEntry("ai_config_id", aiConfigId.toString())
                .containsEntry("retrieval_enabled", false)
                .containsEntry("retention_days", 30)
                .doesNotContainKey("send_user_context");
    }

    @Test
    void updateAuditsAClearedBindingAsNull() {
        when(service.getOrDefault(organizationId)).thenReturn(view(false, aiConfigId, true, 90));
        when(service.update(any(), any())).thenReturn(view(false, null, true, 90));

        controller.update(new UpdateHelpAgentConfigRequest(null, null, true, null, null, null, null,
                null, null, null, null), authentication, auditContext);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().metadata())
                .containsEntry("ai_config_bound", false)
                .doesNotContainKey("ai_config_id");
    }

    @Test
    void updateStillReturnsWhenTheAuditWriteFails() {
        when(service.getOrDefault(organizationId)).thenReturn(view(false, null, true, 90));
        when(service.update(any(), any())).thenReturn(view(true, aiConfigId, true, 90));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down"))
                .when(auditLogService).record(any());

        var body = new UpdateHelpAgentConfigRequest(true, aiConfigId, null, null, null, null, null,
                null, null, null, null);

        assertThat(controller.update(body, authentication, auditContext).enabled()).isTrue();
    }

    @Test
    void testDelegatesToTheService() {
        when(service.testConnection(organizationId))
                .thenReturn(HelpAgentConnectionTestResult.ok("reachable", 1536));

        var response = controller.test(authentication);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.detail()).isEqualTo("reachable");
        assertThat(response.embeddingDimensions()).isEqualTo(1536);
    }

    @Test
    void testMapsAFailure() {
        when(service.testConnection(organizationId))
                .thenReturn(HelpAgentConnectionTestResult.error("no route"));

        var response = controller.test(authentication);

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(response.embeddingDimensions()).isNull();
    }

    @Test
    void reindexAcceptsAndDelegates() {
        var response = controller.reindex(authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(service).requestReindex(organizationId);
    }

    @Test
    void requestMapsEveryFieldOntoTheCommand() {
        var command = new UpdateHelpAgentConfigRequest(true, aiConfigId, true, false, 7, 0.6, 9,
                1500, false, 45, 12).toCommand();

        assertThat(command.enabled()).isTrue();
        assertThat(command.aiConfigId()).isEqualTo(aiConfigId);
        assertThat(command.clearAiConfig()).isTrue();
        assertThat(command.retrievalEnabled()).isFalse();
        assertThat(command.topK()).isEqualTo(7);
        assertThat(command.similarityThreshold()).isEqualTo(0.6);
        assertThat(command.maxHistoryTurns()).isEqualTo(9);
        assertThat(command.maxQuestionChars()).isEqualTo(1500);
        assertThat(command.sendUserContext()).isFalse();
        assertThat(command.retentionDays()).isEqualTo(45);
        assertThat(command.perUserRequestsPerMinute()).isEqualTo(12);
    }

    @Test
    void anAbsentClearFlagIsNotAClear() {
        var command = new UpdateHelpAgentConfigRequest(null, null, null, null, null, null, null,
                null, null, null, null).toCommand();

        assertThat(command.clearAiConfig()).isFalse();
    }

    private HelpAgentConfigView view(boolean enabled, UUID boundAiConfigId, boolean retrievalEnabled,
                                     int retentionDays) {
        return new HelpAgentConfigView(configId, organizationId, enabled, boundAiConfigId,
                retrievalEnabled, 6, 0.4, 8, 2000, true, retentionDays, 6, "c0ac599ef7fc",
                Instant.now(), null, Instant.now(), Instant.now());
    }
}
