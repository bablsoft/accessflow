package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Thrown when no version with the given id exists under the given template within the caller's
 * visibility scope (AF-442). The handler maps this to HTTP 404.
 */
public final class QueryTemplateVersionNotFoundException extends RuntimeException {

    private final UUID templateId;
    private final UUID versionId;

    public QueryTemplateVersionNotFoundException(UUID templateId, UUID versionId) {
        super("Query template version not found: " + versionId + " (template " + templateId + ")");
        this.templateId = templateId;
        this.versionId = versionId;
    }

    public UUID templateId() {
        return templateId;
    }

    public UUID versionId() {
        return versionId;
    }
}
