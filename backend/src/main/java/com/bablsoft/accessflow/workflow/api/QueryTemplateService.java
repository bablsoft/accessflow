package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Saved SQL snippets analysts can load into {@code /editor}. The service is the single source of
 * truth for visibility enforcement: {@link QueryTemplateVisibility#PRIVATE} templates are visible
 * only to the owner; {@link QueryTemplateVisibility#TEAM} templates are visible to every user in
 * the same organisation. Either way, only the owner may mutate or delete a template.
 *
 * <p>Templates are a pure save / load surface — submission still flows through the standard
 * {@code POST /api/v1/queries} pipeline (AI analysis + review). {@code :param} placeholders in the
 * body are stored verbatim; substitution happens on the client before submit.
 */
public interface QueryTemplateService {

    PageResponse<QueryTemplateView> list(UUID organizationId, UUID callerUserId,
                                         QueryTemplateFilter filter, PageRequest pageRequest);

    QueryTemplateView get(UUID id, UUID organizationId, UUID callerUserId);

    QueryTemplateView create(CreateQueryTemplateCommand command);

    QueryTemplateView update(UUID id, UUID organizationId, UUID callerUserId,
                             UpdateQueryTemplateCommand command);

    void delete(UUID id, UUID organizationId, UUID callerUserId);

    /**
     * Restores the template to a prior version's content (AF-442). Restore preserves history — it
     * applies the snapshot's fields to the current template and records a new {@code RESTORED}
     * version rather than deleting any. Owner-only, subject to the same name-uniqueness rules as a
     * normal update.
     *
     * @throws QueryTemplateNotFoundException        if the template is not visible to the caller
     * @throws QueryTemplateAccessDeniedException    if the caller can see but does not own the template
     * @throws QueryTemplateVersionNotFoundException if no such version exists under the template
     * @throws QueryTemplateNameAlreadyExistsException if the restored name now collides with another
     *                                                 template owned by the caller
     */
    QueryTemplateView restoreVersion(UUID templateId, UUID versionId, UUID organizationId,
                                     UUID callerUserId);

    record CreateQueryTemplateCommand(
            UUID organizationId,
            UUID ownerId,
            UUID datasourceId,
            String name,
            String body,
            String description,
            List<String> tags,
            QueryTemplateVisibility visibility) {
    }

    record UpdateQueryTemplateCommand(
            UUID datasourceId,
            String name,
            String body,
            String description,
            List<String> tags,
            QueryTemplateVisibility visibility) {
    }
}
