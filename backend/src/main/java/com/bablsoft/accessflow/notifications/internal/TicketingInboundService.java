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
import com.bablsoft.accessflow.notifications.internal.codec.TicketingChannelConfig;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.bablsoft.accessflow.workflow.api.ExternalDecisionService;
import com.bablsoft.accessflow.workflow.api.ExternalTicketDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles inbound ticket-status callbacks from ServiceNow / Jira (AF-453). The webhook URL carries
 * the channel id; the request is authenticated by the {@code X-AccessFlow-Signature} HMAC keyed by
 * that channel's {@code webhook_secret} (plus a Redis replay guard). A verified update always
 * refreshes the linked {@code query_tickets} row; when the channel opted into
 * {@code bidirectional_sync} and the new status/resolution matches the channel's approve/reject
 * lists, the still-pending query is decided through the workflow's
 * {@link ExternalDecisionService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketingInboundService {

    /** Outcome handed back to the controller: HTTP status + machine-readable result label. */
    public record InboundResult(int status, String result) {

        static InboundResult of(int status, String result) {
            return new InboundResult(status, result);
        }
    }

    private final NotificationChannelRepository channelRepository;
    private final ChannelConfigCodec codec;
    private final TicketingRequestVerifier verifier;
    private final TicketingReplayGuard replayGuard;
    private final QueryTicketService queryTicketService;
    private final ExternalDecisionService externalDecisionService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InboundResult handle(NotificationChannelType system, UUID channelId, String rawBody,
                                String timestampHeader, String signatureHeader) {
        var channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || !channel.isActive() || channel.getChannelType() != system) {
            return InboundResult.of(404, "unknown_channel");
        }
        TicketingChannelConfig config;
        try {
            config = decodeConfig(system, channel);
        } catch (NotificationChannelConfigException ex) {
            log.warn("Ticketing webhook for channel {} has an unreadable config", channelId, ex);
            return InboundResult.of(404, "unknown_channel");
        }
        if (!verifier.isValid(rawBody, timestampHeader, signatureHeader,
                config.webhookSecretPlain(), clock.instant())
                || !replayGuard.firstSeen(signatureHeader)) {
            return InboundResult.of(401, "invalid_signature");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (RuntimeException ex) {
            return InboundResult.of(400, "invalid_payload");
        }
        var externalId = payload.path("external_id").asString("");
        var status = payload.path("status").asString("");
        if (externalId.isBlank() || status.isBlank()) {
            return InboundResult.of(400, "invalid_payload");
        }
        var resolution = blankToNull(payload.path("resolution").asString(""));
        var actor = blankToNull(payload.path("actor").asString(""));

        var updated = queryTicketService.updateStatus(channelId, externalId, status, resolution);
        if (updated.isEmpty()) {
            log.debug("Ticketing webhook for channel {} references unknown ticket {}", channelId,
                    externalId);
            return InboundResult.of(200, "ignored");
        }
        var ticket = updated.get();
        writeSyncedAudit(channel, ticket, status, resolution, actor);

        if (!config.bidirectionalSync()) {
            return InboundResult.of(200, "synced");
        }
        var decision = mapDecision(config, status, resolution);
        if (decision == null) {
            return InboundResult.of(200, "synced");
        }
        var applied = externalDecisionService.applyTicketDecision(
                ticket.queryRequestId(),
                ticket.organizationId(),
                decision,
                decisionReason(system, ticket, status, resolution, actor));
        return InboundResult.of(200, applied ? "decision_applied" : "synced");
    }

    private TicketingChannelConfig decodeConfig(NotificationChannelType system,
                                                NotificationChannelEntity channel) {
        return system == NotificationChannelType.SERVICENOW
                ? codec.decodeServiceNow(channel.getConfigJson())
                : codec.decodeJira(channel.getConfigJson());
    }

    /**
     * Maps the inbound status/resolution onto a decision using the channel's approve/reject lists
     * (case-insensitive; resolution is checked first, then status). Returns null for no match.
     */
    private static ExternalTicketDecision mapDecision(TicketingChannelConfig config, String status,
                                                      String resolution) {
        if (matches(config.rejectStatuses(), resolution) || matches(config.rejectStatuses(), status)) {
            return ExternalTicketDecision.REJECT;
        }
        if (matches(config.approveStatuses(), resolution) || matches(config.approveStatuses(), status)) {
            return ExternalTicketDecision.APPROVE;
        }
        return null;
    }

    private static boolean matches(List<String> candidates, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        return candidates.stream()
                .anyMatch(candidate -> candidate.trim().toLowerCase(Locale.ROOT).equals(normalized));
    }

    private static String decisionReason(NotificationChannelType system, QueryTicketView ticket,
                                         String status, String resolution, String actor) {
        var sb = new StringBuilder();
        sb.append(system == NotificationChannelType.SERVICENOW ? "ServiceNow" : "Jira")
                .append(" ticket ").append(ticket.externalKey())
                .append(" moved to '").append(status).append('\'');
        if (resolution != null) {
            sb.append(" (").append(resolution).append(')');
        }
        if (actor != null) {
            sb.append(" by ").append(actor);
        }
        return sb.toString();
    }

    private void writeSyncedAudit(NotificationChannelEntity channel, QueryTicketView ticket,
                                  String status, String resolution, String actor) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("ticket_system", channel.getChannelType().name());
            metadata.put("channel_id", channel.getId().toString());
            metadata.put("external_key", ticket.externalKey());
            metadata.put("query_request_id", ticket.queryRequestId().toString());
            metadata.put("status", status);
            if (resolution != null) {
                metadata.put("resolution", resolution);
            }
            if (actor != null) {
                metadata.put("actor", actor);
            }
            auditLogService.record(new AuditEntry(
                    AuditAction.TICKET_STATUS_SYNCED,
                    AuditResourceType.QUERY_TICKET,
                    ticket.queryRequestId(),
                    ticket.organizationId(),
                    null,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Failed to write TICKET_STATUS_SYNCED audit row for ticket {}",
                    ticket.externalKey(), ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
