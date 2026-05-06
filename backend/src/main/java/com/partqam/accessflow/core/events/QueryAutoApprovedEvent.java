package com.partqam.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a query is auto-approved by the state machine on AI completion (either because
 * {@code requires_human_approval=false} or the {@code auto_approve_reads} fast-path applied).
 */
public record QueryAutoApprovedEvent(UUID queryRequestId) {
}
