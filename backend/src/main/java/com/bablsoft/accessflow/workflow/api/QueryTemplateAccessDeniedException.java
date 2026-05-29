package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when a caller is allowed to see a {@link QueryTemplateVisibility#TEAM} template but is not
 * its owner, and attempts to mutate or delete it. The handler maps this to HTTP 403 (not 404) so
 * the caller knows the template exists — they already saw it in the list.
 */
public final class QueryTemplateAccessDeniedException extends RuntimeException {

    private final UUID templateId;

    public QueryTemplateAccessDeniedException(UUID templateId) {
        super("Caller is not the owner of query template: " + templateId);
        this.templateId = templateId;
    }

    public UUID templateId() {
        return templateId;
    }
}
