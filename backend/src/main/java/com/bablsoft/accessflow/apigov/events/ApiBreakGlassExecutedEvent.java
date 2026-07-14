package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/**
 * Published synchronously after a break-glass API call has been force-approved and executed
 * (AF-500). The workflow module listens in the same transaction to open the mandatory retro-review
 * ({@code break_glass_events}) — event-based so apigov does not depend on workflow (which would
 * close an access → apigov → workflow → access module cycle, AF-567).
 */
public record ApiBreakGlassExecutedEvent(
        UUID organizationId,
        UUID apiRequestId,
        UUID connectorId,
        UUID submitterUserId,
        String justification) {
}
