package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Submission, listing, cancellation, and submitter-triggered execution of governed API calls. The
 * call flows through AI risk scoring → routing → human review (self-approval forbidden) before it can
 * execute, exactly like a database query.
 */
public interface ApiRequestService {

    ApiRequestSubmissionResult submit(SubmitApiRequestCommand command);

    /**
     * Lists governed API requests matching {@code filter}. Admins pass a filter with a {@code null}
     * {@code submittedByUserId} to see the whole organization; everyone else sets it to their own id.
     */
    PageResponse<ApiRequestView> list(ApiRequestListFilter filter, PageRequest pageRequest);

    ApiRequestView get(UUID id, UUID organizationId, UUID userId, boolean admin);

    /** Submitter cancels a request that is still pending (or APPROVED + scheduled, not yet run). */
    void cancel(UUID id, UUID organizationId, UUID userId);

    /** Submitter triggers execution of an APPROVED request (synchronous; deferred runs use the job). */
    ApiRequestView execute(UUID id, UUID organizationId, UUID userId, boolean admin);

    /**
     * Returns the stored (masked, size-capped) response snapshot of an executed request for download,
     * together with its captured content type and a suggested filename. Same ownership guard as
     * {@link #get}.
     */
    ApiResponsePayload downloadResponse(UUID id, UUID organizationId, UUID userId, boolean admin);
}
