package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/** A scheduled or manual refresh stored a new schema revision with at least one drift finding (#893). */
public record ApiSchemaDriftDetectedEvent(UUID schemaId, UUID connectorId, UUID organizationId,
                                          UUID revisionId, int findingCount) {
}
