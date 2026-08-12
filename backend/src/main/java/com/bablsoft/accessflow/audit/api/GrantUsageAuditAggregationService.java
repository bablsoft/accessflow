package com.bablsoft.accessflow.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only projection of grant <em>usage</em> from {@code audit_log} (#625). A sibling of
 * {@link BehaviorAuditAggregationService} rather than an extension of it: UBA asks "what did this
 * one known subject do on this one datasource", whereas least-privilege intelligence asks "which
 * grants were exercised at all", across both datasources and API connectors. Lives in the audit
 * module so the JSONB metadata parsing stays with the table that owns it; consumers see only pure
 * DTOs. Reads only — compatible with the SELECT-only application role on {@code audit_log}.
 */
public interface GrantUsageAuditAggregationService {

    /**
     * Successful grant-use events for one organization in {@code [from, to)}, oldest first, capped
     * at {@code maxRows}. A caller that receives exactly {@code maxRows} events must resume from the
     * last event's timestamp rather than from {@code to}, or it will skip the tail of the window.
     *
     * <p><strong>The organization scope is required, not a convenience.</strong> {@code audit_log}
     * is indexed on {@code (organization_id, created_at DESC)} and has no index on {@code action},
     * so an org-scoped range read is the only shape that stays off a sequential scan. Adding an
     * index is not an option: since {@code V38} the table is owned by the dedicated audit role
     * while Flyway runs as the application role.
     *
     * <p>Counts the four execution actions — {@code QUERY_EXECUTED},
     * {@code QUERY_BREAK_GLASS_EXECUTED}, {@code API_REQUEST_EXECUTED} and
     * {@code API_REQUEST_BREAK_GLASS_EXECUTED}. Break-glass is included because emergency access is
     * unambiguously use of a grant — omitting it would let a grant exercised only under break-glass
     * look abandoned. Failures are excluded: they carry no referenced tables, and a query that did
     * not run is not evidence that its granted scope is needed.
     */
    List<GrantUsageAuditEvent> findUsageEvents(UUID organizationId, Instant from, Instant to,
                                               int maxRows);
}
