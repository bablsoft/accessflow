package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published after a datasource credential secret reference was successfully resolved through an
 * external store (AF-448). Never carries the secret value. {@code datasourceId} and
 * {@code organizationId} are {@code null} when the resolve happened on the context-less engine
 * lane — the audit listener attributes those by looking the reference up against the datasource
 * table.
 */
public record SecretReferenceResolvedEvent(String provider, String reference, UUID datasourceId,
                                           UUID organizationId) {
}
