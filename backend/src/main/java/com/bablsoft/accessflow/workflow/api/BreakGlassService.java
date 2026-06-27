package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Break-glass / emergency access (AF-385). A distinct submission mode that skips pre-approval and
 * executes immediately through all the usual proxy guards (allow-list, masking, row-security, row
 * caps), then opens a mandatory retro-review. Gated by a per-user/per-datasource
 * {@code can_break_glass} grant required for everyone — including admins.
 *
 * <p>Unlike {@link QuerySubmissionService#submit}, this runs synchronously: the returned result
 * reflects the completed execution. No {@code QuerySubmittedEvent} is published (AI analysis and
 * human review are intentionally bypassed).
 */
public interface BreakGlassService {

    /**
     * Persists, force-approves, and immediately executes a break-glass query, opening a
     * {@link BreakGlassStatus#PENDING_REVIEW} retro-review and fanning out to all org admins.
     *
     * @throws BreakGlassNotPermittedException if the caller has no effective {@code can_break_glass}
     *         grant (or lacks the capability / allow-list) for the datasource.
     * @throws com.bablsoft.accessflow.core.api.InvalidSqlException if the SQL is unparseable or the
     *         query type is unsupported.
     */
    BreakGlassResult breakGlassExecute(BreakGlassInput input);

    /**
     * Opens a mandatory break-glass retro-review row for an API break-glass execution (AF-500). The
     * apigov module persists, force-approves, and executes the API call itself; this records the
     * {@link BreakGlassStatus#PENDING_REVIEW} event an admin must later acknowledge. Returns the
     * event id.
     */
    UUID openApiBreakGlassReview(ApiBreakGlassReview review);

    record ApiBreakGlassReview(
            UUID organizationId,
            UUID apiRequestId,
            UUID connectorId,
            UUID submitterUserId,
            String justification) {
    }

    record BreakGlassInput(
            UUID datasourceId,
            String sql,
            String justification,
            UUID submitterUserId,
            UUID organizationId,
            boolean isAdmin,
            String submittedIp,
            String submittedUserAgent) {
    }

    record BreakGlassResult(
            UUID queryRequestId,
            UUID eventId,
            QueryStatus status,
            Long rowsAffected,
            Integer durationMs) {
    }
}
