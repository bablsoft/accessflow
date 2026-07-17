package com.bablsoft.accessflow.notifications.internal.codec;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Typed view of a {@code SERVICENOW} channel config with decrypted secrets (AF-453). Incidents are
 * created through the Table API ({@code POST /api/now/table/incident}) with Basic auth
 * {@code username:password}. {@code urgency} is ServiceNow's 1–3 scale (1 = high), null to let the
 * instance default apply; {@code assignmentGroup} is the target group's name or sys_id, null to
 * leave unassigned.
 */
public record ServiceNowChannelConfig(
        URI instanceUrl,
        String username,
        String passwordPlain,
        String assignmentGroup,
        Integer urgency,
        Set<TicketingTrigger> triggers,
        boolean bidirectionalSync,
        String webhookSecretPlain,
        List<String> approveStatuses,
        List<String> rejectStatuses) implements TicketingChannelConfig {
}
