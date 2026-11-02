package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Replays a terminal governed API request as a new submission (#886). The stored call is re-submitted
 * through {@link ApiRequestService#submit} with the caller as submitter, no schedule and
 * {@code USER_SUBMITTED} — a replay never re-executes the source row and never bypasses AI, routing or
 * review. Only the original submitter may replay: the stored headers, body and form files are shown
 * to nobody else, so replaying another user's request would resend input the caller never saw.
 * Credentials
 * and resolved dynamic variables are never copied; they resolve at execution time. The replay is
 * audited on the new request's {@code API_REQUEST_SUBMITTED} row with {@code trigger=replay}.
 */
public interface ApiRequestReplayService {

    /**
     * Re-submits the stored call, dropping any variable override the caller may no longer supply.
     *
     * @throws ApiRequestNotFoundException if the source is in another organization or the caller is
     *         not its submitter.
     * @throws IllegalApiRequestStateException if the source is not terminal.
     * @throws ApiReplayOperationUnavailableException if the source's operation is no longer in the
     *         connector's catalog.
     */
    ReplayResult replay(ReplayCommand command);

    record ReplayCommand(UUID originalApiRequestId, UUID organizationId, UUID callerUserId,
                         boolean admin, String ipAddress,
                         String userAgent,
                         /** The human an API-key caller acts for (#874); null for a human. */
                         UUID onBehalfOfUserId) {
    }

    record ReplayResult(UUID newApiRequestId, QueryStatus status, int droppedVariableOverrideCount) {
    }
}
