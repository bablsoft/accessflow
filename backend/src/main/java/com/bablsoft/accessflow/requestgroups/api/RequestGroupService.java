package com.bablsoft.accessflow.requestgroups.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Build, submit, list, cancel, and submitter-triggered execution of grouped requests. A group bundles
 * ordered query and/or API-call members; it is reviewed and approved as one element (union of member
 * plans, every plan satisfied) and executed as an ordered sequence — there is no distributed
 * rollback.
 */
public interface RequestGroupService {

    /** Create a {@code DRAFT} group with its ordered members. */
    RequestGroupView createDraft(CreateRequestGroupCommand command);

    /** Replace a {@code DRAFT} group's fields + members (submitter only). */
    RequestGroupView updateDraft(UpdateRequestGroupCommand command);

    /** Submit a {@code DRAFT} group for AI + review (or break-glass force-approve). */
    RequestGroupSubmissionResult submit(SubmitRequestGroupCommand command);

    PageResponse<RequestGroupView> list(RequestGroupListFilter filter, PageRequest pageRequest);

    RequestGroupView get(UUID id, UUID organizationId, UUID userId, boolean admin);

    /** Submitter deletes a {@code DRAFT} group (and its members). */
    void deleteDraft(UUID id, UUID organizationId, UUID userId);

    /** Submitter cancels a group that is still pending (or APPROVED + scheduled, not yet run). */
    void cancel(UUID id, UUID organizationId, UUID userId);

    /** Submitter triggers the ordered run of an APPROVED group (synchronous; deferred runs use the job). */
    RequestGroupView execute(UUID id, UUID organizationId, UUID userId, boolean admin);
}
