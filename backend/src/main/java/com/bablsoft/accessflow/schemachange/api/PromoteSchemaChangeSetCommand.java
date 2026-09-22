package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/** Asks for a change set to be promoted to one deployment environment (#878, epic #870). */
public record PromoteSchemaChangeSetCommand(UUID environmentId) {
}
