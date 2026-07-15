package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigNotFoundException;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigService;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigView;
import com.bablsoft.accessflow.notifications.internal.DecryptedSlackApp;
import com.bablsoft.accessflow.notifications.internal.DefaultSlackAppConfigService;
import com.bablsoft.accessflow.notifications.internal.SlackMessages;
import com.bablsoft.accessflow.notifications.internal.strategy.SlackBotMessenger;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSlackAppConfigControllerTest {

    @Mock SlackAppConfigService slackAppConfigService;
    @Mock DefaultSlackAppConfigService slackAppRuntime;
    @Mock SlackBotMessenger botMessenger;
    @Mock SlackMessages messages;
    @Mock AuditLogService auditLogService;

    private AdminSlackAppConfigController controller;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("1.2.3.4", "ua/1");

    @BeforeEach
    void setUp() {
        controller = new AdminSlackAppConfigController(
                slackAppConfigService, slackAppRuntime, botMessenger, messages, auditLogService);
        lenient().when(messages.forOrg(any(), anyString())).thenAnswer(i -> i.getArgument(1));
    }

    private UsernamePasswordAuthenticationToken auth() {
        var claims = JwtClaims.forSystemRole(userId, "admin@example.com", UserRoleType.ADMIN, orgId);
        return new UsernamePasswordAuthenticationToken(claims, "n/a", List.of());
    }

    private SlackAppConfigView view() {
        return new SlackAppConfigView(UUID.randomUUID(), orgId, "A1", "C1", true, true, true,
                Instant.now(), Instant.now());
    }

    @Test
    void getReturns200WhenConfigured() {
        when(slackAppConfigService.get(orgId)).thenReturn(Optional.of(view()));

        var resp = controller.get(auth());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().appId()).isEqualTo("A1");
    }

    @Test
    void getReturns404WhenUnconfigured() {
        when(slackAppConfigService.get(orgId)).thenReturn(Optional.empty());

        var resp = controller.get(auth());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void upsertSavesAndRecordsAudit() {
        when(slackAppConfigService.upsert(any(), any())).thenReturn(view());

        var resp = controller.upsert(
                new UpsertSlackAppConfigRequest("A1", "C1", "xoxb", "sign", true), auth(), auditContext);

        assertThat(resp.appId()).isEqualTo("A1");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void upsertSwallowsAuditFailure() {
        when(slackAppConfigService.upsert(any(), any())).thenReturn(view());
        doThrow(new RuntimeException("audit down")).when(auditLogService).record(any(AuditEntry.class));

        var resp = controller.upsert(
                new UpsertSlackAppConfigRequest("A1", "C1", "xoxb", "sign", true), auth(), auditContext);

        assertThat(resp.appId()).isEqualTo("A1");
    }

    @Test
    void deleteReturns204AndRecordsAudit() {
        var resp = controller.delete(auth(), auditContext);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(slackAppConfigService).delete(orgId);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void testReturnsOkWhenBotPostSucceeds() {
        when(slackAppRuntime.findDecryptedByOrg(orgId))
                .thenReturn(Optional.of(new DecryptedSlackApp(orgId, "A1", "xoxb", "sign", "C1")));

        var resp = controller.test(auth());

        assertThat(resp.status()).isEqualTo("OK");
        verify(botMessenger).postMessage(eq("xoxb"), eq("C1"), anyString(), any());
    }

    @Test
    void testReturnsErrorWhenBotPostFails() {
        when(slackAppRuntime.findDecryptedByOrg(orgId))
                .thenReturn(Optional.of(new DecryptedSlackApp(orgId, "A1", "xoxb", "sign", "C1")));
        doThrow(new RuntimeException("channel_not_found"))
                .when(botMessenger).postMessage(any(), any(), any(), any());

        var resp = controller.test(auth());

        assertThat(resp.status()).isEqualTo("ERROR");
        assertThat(resp.detail()).contains("channel_not_found");
    }

    @Test
    void testThrowsWhenUnconfigured() {
        when(slackAppRuntime.findDecryptedByOrg(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.test(auth()))
                .isInstanceOf(SlackAppConfigNotFoundException.class);
    }
}
