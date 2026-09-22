package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * Asks for a change set to be promoted to one deployment environment (#878, epic #870).
 * {@code submittedIp} / {@code submittedUserAgent} are the caller's request provenance (#880),
 * recorded on the request group and the audit row; both null off an HTTP thread.
 */
public record PromoteSchemaChangeSetCommand(UUID environmentId, String submittedIp, String submittedUserAgent) {

    /** Backward-compatible constructor without request provenance. */
    public PromoteSchemaChangeSetCommand(UUID environmentId) {
        this(environmentId, null, null);
    }
}
