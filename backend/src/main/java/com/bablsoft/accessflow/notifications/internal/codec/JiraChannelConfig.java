package com.bablsoft.accessflow.notifications.internal.codec;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Typed view of a {@code JIRA} channel config with decrypted secrets (AF-453). Issues are created
 * through the REST v2 API ({@code POST /rest/api/2/issue} — v2 deliberately, its plain-text
 * {@code description} avoids the Atlassian Document Format that v3 requires) with Basic auth
 * {@code userEmail:apiToken}.
 */
public record JiraChannelConfig(
        URI baseUrl,
        String userEmail,
        String apiTokenPlain,
        String projectKey,
        String issueType,
        Set<TicketingTrigger> triggers,
        boolean bidirectionalSync,
        String webhookSecretPlain,
        List<String> approveStatuses,
        List<String> rejectStatuses) implements TicketingChannelConfig {
}
