package com.bablsoft.accessflow.notifications.internal.codec;

import java.util.List;
import java.util.Set;

/**
 * Shape shared by the ticketing channel configs (ServiceNow / Jira, AF-453): which workflow events
 * open a ticket, and how inbound ticket-status webhooks map onto query decisions.
 *
 * <p>{@code approveStatuses} / {@code rejectStatuses} are matched case-insensitively against the
 * inbound payload's {@code status} and {@code resolution}; a match only drives a query decision
 * when {@code bidirectionalSync} is enabled for the channel.
 */
public sealed interface TicketingChannelConfig permits ServiceNowChannelConfig, JiraChannelConfig {

    List<String> DEFAULT_APPROVE_STATUSES =
            List.of("resolved", "closed", "done", "approved", "complete");
    List<String> DEFAULT_REJECT_STATUSES =
            List.of("rejected", "declined", "cancelled", "canceled", "won't do");

    Set<TicketingTrigger> triggers();

    boolean bidirectionalSync();

    /** Decrypted shared secret for inbound webhook HMAC verification; null when sync is off. */
    String webhookSecretPlain();

    List<String> approveStatuses();

    List<String> rejectStatuses();
}
