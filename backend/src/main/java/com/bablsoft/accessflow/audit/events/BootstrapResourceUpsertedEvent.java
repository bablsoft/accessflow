package com.bablsoft.accessflow.audit.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published by the bootstrap reconcilers when an env-driven spec causes a real INSERT or UPDATE.
 * No-op reconciles (spec fingerprint matches the stored fingerprint) MUST NOT publish.
 *
 * <p>Consumed by the audit module's listener which writes an {@code audit_log} row with
 * {@code actor_id = NULL} and {@code metadata.source = "BOOTSTRAP"}, plus the {@code changedFields}
 * and {@code summaryMetadata} payload. Singleton org-level configs (SAML, SystemSmtp) use the
 * organization id as {@code resourceId}; OAuth2-per-provider rows use a deterministic UUID derived
 * from the provider name.
 */
public record BootstrapResourceUpsertedEvent(
        UUID organizationId,
        BootstrapResourceType resourceType,
        UUID resourceId,
        BootstrapChangeKind changeKind,
        List<String> changedFields,
        Map<String, Object> summaryMetadata) {

    public BootstrapResourceUpsertedEvent {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (resourceType == null) {
            throw new IllegalArgumentException("resourceType is required");
        }
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (changeKind == null) {
            throw new IllegalArgumentException("changeKind is required");
        }
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
        summaryMetadata = summaryMetadata == null ? Map.of() : Map.copyOf(summaryMetadata);
    }
}
