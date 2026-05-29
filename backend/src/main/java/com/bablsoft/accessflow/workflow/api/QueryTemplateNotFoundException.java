package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when no query template matches the given id within the caller's visibility scope.
 * The handler maps this to HTTP 404 — we deliberately do not distinguish "exists but invisible"
 * from "does not exist" so PRIVATE templates owned by another user are not leaked.
 */
public final class QueryTemplateNotFoundException extends RuntimeException {

    private final UUID templateId;

    public QueryTemplateNotFoundException(UUID templateId) {
        super("Query template not found: " + templateId);
        this.templateId = templateId;
    }

    public UUID templateId() {
        return templateId;
    }
}
