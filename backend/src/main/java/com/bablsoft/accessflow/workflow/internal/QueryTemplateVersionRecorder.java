package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;

import java.util.UUID;

/**
 * Module-internal collaborator that writes immutable version snapshots and reads version entities
 * for the restore path (AF-442). Kept out of {@code workflow.api} because it traffics in entity
 * types; {@link DefaultQueryTemplateService} depends on it directly, which keeps the dependency
 * one-way (template service → versioning service) and free of a Spring bean cycle.
 */
interface QueryTemplateVersionRecorder {

    /**
     * Records a snapshot of the template's current state. {@code UPDATED} is a no-op when the
     * content is identical to the latest snapshot; {@code CREATED} and {@code RESTORED} always
     * insert. The insert runs in the caller's transaction.
     */
    void recordSnapshot(QueryTemplateEntity template, UUID authorId, QueryTemplateChangeType changeType);

    /** Loads a version entity scoped to its template, throwing if it does not exist. */
    QueryTemplateVersionEntity requireVersion(UUID templateId, UUID versionId);
}
