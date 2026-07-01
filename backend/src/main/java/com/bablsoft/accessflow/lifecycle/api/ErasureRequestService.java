package com.bablsoft.accessflow.lifecycle.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Self-service operations on right-to-erasure requests: submit a request for a data subject, list
 * one's own requests, and cancel a request that has not yet been decided.
 */
public interface ErasureRequestService {

    ErasureRequestView submit(SubmitErasureCommand command);

    PageResponse<ErasureRequestView> listMine(UUID organizationId, UUID requesterId,
                                              PageRequest pageRequest);

    ErasureRequestView get(UUID requestId, UUID organizationId);

    /**
     * Cancels a request owned by {@code requesterId} while it is still
     * {@link ErasureStatus#PENDING_SCOPE_AI} or {@link ErasureStatus#PENDING_REVIEW}. Throws
     * {@link DeletionRequestNotFoundException} when missing/foreign and
     * {@link DeletionRequestInvalidStateException} otherwise.
     */
    void cancel(UUID requestId, UUID requesterId, UUID organizationId);

    record SubmitErasureCommand(
            UUID organizationId,
            UUID datasourceId,
            LifecycleSubjectType subjectType,
            String subjectIdentifier,
            String targetTable,
            List<String> targetColumns,
            ErasureConditionSet conditions,
            String rawWhere,
            String reason,
            UUID requestedBy) {

        public SubmitErasureCommand {
            targetColumns = targetColumns == null ? List.of() : List.copyOf(targetColumns);
        }
    }
}
