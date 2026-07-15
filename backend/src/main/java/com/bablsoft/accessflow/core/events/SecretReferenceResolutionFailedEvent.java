package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when resolving a datasource credential secret reference through its external store
 * failed (AF-448). {@code error} is the resolution failure summary — never the secret value.
 * {@code datasourceId} and {@code organizationId} are {@code null} on the context-less engine
 * lane (see {@link SecretReferenceResolvedEvent}).
 */
public record SecretReferenceResolutionFailedEvent(String provider, String reference,
                                                   UUID datasourceId, UUID organizationId,
                                                   String error) {
}
