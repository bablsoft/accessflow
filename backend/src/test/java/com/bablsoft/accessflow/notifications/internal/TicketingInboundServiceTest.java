package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.QueryTicketService;
import com.bablsoft.accessflow.core.api.QueryTicketView;
import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.bablsoft.accessflow.notifications.internal.codec.JiraChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.ServiceNowChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingChannelConfig;
import com.bablsoft.accessflow.notifications.internal.codec.TicketingTrigger;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.bablsoft.accessflow.workflow.api.ExternalDecisionService;
import com.bablsoft.accessflow.workflow.api.ExternalTicketDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketingInboundServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Mock NotificationChannelRepository channelRepository;
    @Mock ChannelConfigCodec codec;
    @Mock TicketingRequestVerifier verifier;
    @Mock TicketingReplayGuard replayGuard;
    @Mock QueryTicketService queryTicketService;
    @Mock ExternalDecisionService externalDecisionService;
    @Mock AuditLogService auditLogService;

    private TicketingInboundService service;

    private final UUID channelId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID queryRequestId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TicketingInboundService(channelRepository, codec, verifier, replayGuard,
                queryTicketService, externalDecisionService, auditLogService,
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- 404 unknown_channel -------------------------------------------------------------------

    @Test
    void unknownChannelIdReturns404() {
        when(channelRepository.findById(channelId)).thenReturn(Optional.empty());

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.result()).isEqualTo("unknown_channel");
        verifyNoInteractions(verifier, replayGuard, queryTicketService, externalDecisionService);
    }

    @Test
    void inactiveChannelReturns404() {
        var channel = channel(NotificationChannelType.SERVICENOW);
        channel.setActive(false);
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{}", "1", "sha256=x");

        assertThat(result).isEqualTo(new TicketingInboundService.InboundResult(404,
                "unknown_channel"));
    }

    @Test
    void channelTypeMismatchReturns404() {
        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel(NotificationChannelType.JIRA)));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.result()).isEqualTo("unknown_channel");
    }

    @Test
    void unreadableConfigReturns404() {
        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel(NotificationChannelType.SERVICENOW)));
        when(codec.decodeServiceNow(anyString()))
                .thenThrow(new NotificationChannelConfigException("broken"));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.result()).isEqualTo("unknown_channel");
        verifyNoInteractions(verifier, replayGuard);
    }

    // --- 401 invalid_signature -----------------------------------------------------------------

    @Test
    void invalidSignatureReturns401() {
        stubServiceNowChannel(serviceNowConfig(true));
        when(verifier.isValid(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.result()).isEqualTo("invalid_signature");
        verifyNoInteractions(queryTicketService);
    }

    @Test
    void verifierReceivesDecryptedSecretAndFixedClockInstant() {
        stubServiceNowChannel(serviceNowConfig(true));
        when(verifier.isValid(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        service.handle(NotificationChannelType.SERVICENOW, channelId, "{}", "1", "sha256=x");

        verify(verifier).isValid("{}", "1", "sha256=x", "hook-secret", NOW);
    }

    @Test
    void replayedSignatureReturns401() {
        stubServiceNowChannel(serviceNowConfig(true));
        when(verifier.isValid(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(replayGuard.firstSeen("sha256=x")).thenReturn(false);

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.result()).isEqualTo("invalid_signature");
        verifyNoInteractions(queryTicketService);
    }

    // --- 400 invalid_payload -------------------------------------------------------------------

    @Test
    void unparseableJsonReturns400() {
        stubVerifiedServiceNow(serviceNowConfig(true));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{not-json", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.result()).isEqualTo("invalid_payload");
        verifyNoInteractions(queryTicketService);
    }

    @Test
    void missingExternalIdReturns400() {
        stubVerifiedServiceNow(serviceNowConfig(true));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"status\":\"Resolved\"}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.result()).isEqualTo("invalid_payload");
    }

    @Test
    void blankStatusReturns400() {
        stubVerifiedServiceNow(serviceNowConfig(true));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"  \"}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.result()).isEqualTo("invalid_payload");
    }

    // --- 200 ignored ---------------------------------------------------------------------------

    @Test
    void unknownTicketReturns200Ignored() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "Resolved", null))
                .thenReturn(Optional.empty());

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Resolved\"}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.result()).isEqualTo("ignored");
        verifyNoInteractions(auditLogService, externalDecisionService);
    }

    // --- 200 synced ----------------------------------------------------------------------------

    @Test
    void syncedWithoutBidirectionalSyncWritesAuditAndSkipsDecision() {
        stubVerifiedServiceNow(serviceNowConfig(false));
        when(queryTicketService.updateStatus(channelId, "abc", "Resolved", "Fixed"))
                .thenReturn(Optional.of(ticket()));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Resolved\",\"resolution\":\"Fixed\","
                        + "\"actor\":\"jdoe\"}",
                "1", "sha256=x");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.result()).isEqualTo("synced");
        verifyNoInteractions(externalDecisionService);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.TICKET_STATUS_SYNCED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_TICKET);
        assertThat(entry.resourceId()).isEqualTo(queryRequestId);
        assertThat(entry.organizationId()).isEqualTo(orgId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata())
                .containsEntry("ticket_system", "SERVICENOW")
                .containsEntry("channel_id", channelId.toString())
                .containsEntry("external_key", "INC0010023")
                .containsEntry("query_request_id", queryRequestId.toString())
                .containsEntry("status", "Resolved")
                .containsEntry("resolution", "Fixed")
                .containsEntry("actor", "jdoe");
    }

    @Test
    void auditMetadataOmitsResolutionAndActorWhenAbsent() {
        stubVerifiedServiceNow(serviceNowConfig(false));
        when(queryTicketService.updateStatus(channelId, "abc", "In Progress", null))
                .thenReturn(Optional.of(ticket()));

        service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"In Progress\"}", "1", "sha256=x");

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().metadata())
                .doesNotContainKey("resolution")
                .doesNotContainKey("actor");
    }

    @Test
    void auditFailureIsSwallowedAndProcessingContinues() {
        stubVerifiedServiceNow(serviceNowConfig(false));
        when(queryTicketService.updateStatus(channelId, "abc", "New", null))
                .thenReturn(Optional.of(ticket()));
        doThrow(new RuntimeException("audit down")).when(auditLogService)
                .record(any(AuditEntry.class));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"New\"}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.result()).isEqualTo("synced");
    }

    @Test
    void syncEnabledButNoStatusMatchReturnsSynced() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "In Progress", null))
                .thenReturn(Optional.of(ticket()));

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"In Progress\"}", "1", "sha256=x");

        assertThat(result.result()).isEqualTo("synced");
        verifyNoInteractions(externalDecisionService);
    }

    @Test
    void decisionNotAppliedStillReturnsSynced() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "Resolved", null))
                .thenReturn(Optional.of(ticket()));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(false);

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Resolved\"}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.result()).isEqualTo("synced");
    }

    // --- 200 decision_applied ------------------------------------------------------------------

    @Test
    void approveStatusMatchAppliesApproveDecision() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "Resolved", null))
                .thenReturn(Optional.of(ticket()));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(true);

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Resolved\"}", "1", "sha256=x");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.result()).isEqualTo("decision_applied");
        verify(externalDecisionService).applyTicketDecision(eq(queryRequestId), eq(orgId),
                eq(ExternalTicketDecision.APPROVE), anyString());
    }

    @Test
    void statusMatchIsCaseInsensitive() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "RESOLVED", null))
                .thenReturn(Optional.of(ticket()));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(true);

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"RESOLVED\"}", "1", "sha256=x");

        assertThat(result.result()).isEqualTo("decision_applied");
        verify(externalDecisionService).applyTicketDecision(any(), any(),
                eq(ExternalTicketDecision.APPROVE), anyString());
    }

    @Test
    void rejectListWinsOverApproveListWhenBothMatch() {
        // status matches the approve list, resolution matches the reject list → REJECT.
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "Resolved", "Rejected"))
                .thenReturn(Optional.of(ticket()));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(true);

        var result = service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Resolved\",\"resolution\":\"Rejected\"}",
                "1", "sha256=x");

        assertThat(result.result()).isEqualTo("decision_applied");
        verify(externalDecisionService).applyTicketDecision(any(), any(),
                eq(ExternalTicketDecision.REJECT), anyString());
    }

    @Test
    void rejectStatusAppliesRejectDecision() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "Declined", null))
                .thenReturn(Optional.of(ticket()));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(true);

        service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Declined\"}", "1", "sha256=x");

        verify(externalDecisionService).applyTicketDecision(any(), any(),
                eq(ExternalTicketDecision.REJECT), anyString());
    }

    @Test
    void decisionReasonCarriesTicketProvenance() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "Resolved", "Fixed"))
                .thenReturn(Optional.of(ticket()));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(true);

        service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Resolved\",\"resolution\":\"Fixed\","
                        + "\"actor\":\"jdoe\"}",
                "1", "sha256=x");

        var reason = ArgumentCaptor.forClass(String.class);
        verify(externalDecisionService).applyTicketDecision(eq(queryRequestId), eq(orgId),
                eq(ExternalTicketDecision.APPROVE), reason.capture());
        assertThat(reason.getValue()).isEqualTo(
                "ServiceNow ticket INC0010023 moved to 'Resolved' (Fixed) by jdoe");
    }

    @Test
    void decisionReasonOmitsResolutionAndActorWhenAbsent() {
        stubVerifiedServiceNow(serviceNowConfig(true));
        when(queryTicketService.updateStatus(channelId, "abc", "Resolved", null))
                .thenReturn(Optional.of(ticket()));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(true);

        service.handle(NotificationChannelType.SERVICENOW, channelId,
                "{\"external_id\":\"abc\",\"status\":\"Resolved\"}", "1", "sha256=x");

        var reason = ArgumentCaptor.forClass(String.class);
        verify(externalDecisionService).applyTicketDecision(any(), any(), any(), reason.capture());
        assertThat(reason.getValue())
                .isEqualTo("ServiceNow ticket INC0010023 moved to 'Resolved'");
    }

    // --- Jira branch ---------------------------------------------------------------------------

    @Test
    void jiraChannelDecodesJiraConfigAndUsesJiraReasonLabel() {
        var channel = channel(NotificationChannelType.JIRA);
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        when(codec.decodeJira("{cfg}")).thenReturn(jiraConfig());
        when(verifier.isValid(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(replayGuard.firstSeen(anyString())).thenReturn(true);
        when(queryTicketService.updateStatus(channelId, "10001", "Done", null))
                .thenReturn(Optional.of(ticket("AF-42")));
        when(externalDecisionService.applyTicketDecision(any(), any(), any(), any()))
                .thenReturn(true);

        var result = service.handle(NotificationChannelType.JIRA, channelId,
                "{\"external_id\":\"10001\",\"status\":\"Done\"}", "1", "sha256=x");

        assertThat(result.result()).isEqualTo("decision_applied");
        verify(codec).decodeJira("{cfg}");
        verify(codec, never()).decodeServiceNow(anyString());
        var reason = ArgumentCaptor.forClass(String.class);
        verify(externalDecisionService).applyTicketDecision(any(), any(),
                eq(ExternalTicketDecision.APPROVE), reason.capture());
        assertThat(reason.getValue()).startsWith("Jira ticket AF-42 moved to 'Done'");
    }

    // --- helpers -------------------------------------------------------------------------------

    private NotificationChannelEntity channel(NotificationChannelType type) {
        var c = new NotificationChannelEntity();
        c.setId(channelId);
        c.setOrganizationId(orgId);
        c.setChannelType(type);
        c.setName("Ticketing");
        c.setActive(true);
        c.setConfigJson("{cfg}");
        c.setCreatedAt(NOW);
        return c;
    }

    private void stubServiceNowChannel(TicketingChannelConfig config) {
        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel(NotificationChannelType.SERVICENOW)));
        when(codec.decodeServiceNow("{cfg}")).thenReturn((ServiceNowChannelConfig) config);
    }

    private void stubVerifiedServiceNow(TicketingChannelConfig config) {
        stubServiceNowChannel(config);
        when(verifier.isValid(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(replayGuard.firstSeen(anyString())).thenReturn(true);
    }

    private ServiceNowChannelConfig serviceNowConfig(boolean bidirectionalSync) {
        return new ServiceNowChannelConfig(
                URI.create("https://dev1234.service-now.com"),
                "integration",
                "pw",
                null,
                null,
                EnumSet.of(TicketingTrigger.QUERY_REJECTED),
                bidirectionalSync,
                "hook-secret",
                TicketingChannelConfig.DEFAULT_APPROVE_STATUSES,
                TicketingChannelConfig.DEFAULT_REJECT_STATUSES);
    }

    private JiraChannelConfig jiraConfig() {
        return new JiraChannelConfig(
                URI.create("https://example.atlassian.net"),
                "bot@example.com",
                "token",
                "AF",
                "Task",
                EnumSet.of(TicketingTrigger.QUERY_ESCALATED),
                true,
                "hook-secret",
                TicketingChannelConfig.DEFAULT_APPROVE_STATUSES,
                TicketingChannelConfig.DEFAULT_REJECT_STATUSES);
    }

    private QueryTicketView ticket() {
        return ticket("INC0010023");
    }

    private QueryTicketView ticket(String externalKey) {
        return new QueryTicketView(ticketId, orgId, queryRequestId, channelId,
                "SERVICENOW", "QUERY_REJECTED", "abc", externalKey,
                "https://dev1234.service-now.com/incident", "Resolved", null, NOW, NOW);
    }
}
