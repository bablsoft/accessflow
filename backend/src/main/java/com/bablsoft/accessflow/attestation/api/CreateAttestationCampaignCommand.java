package com.bablsoft.accessflow.attestation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Command to create a campaign. Carries the resolved organization + creator (from the JWT, never the
 * request body) plus the campaign definition. Business-rule invariants are validated in the compact
 * constructor; field-shape constraints (lengths, non-null) are mirrored on the web request DTO.
 */
public record CreateAttestationCampaignCommand(
        UUID organizationId,
        UUID createdBy,
        String name,
        String description,
        AttestationCampaignScope scope,
        UUID datasourceId,
        AttestationPendingDefault pendingDefault,
        Instant scheduledOpenAt,
        Instant dueAt) {

    public CreateAttestationCampaignCommand {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (createdBy == null) {
            throw new IllegalArgumentException("createdBy is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        if (pendingDefault == null) {
            pendingDefault = AttestationPendingDefault.KEEP;
        }
        if (scheduledOpenAt == null || dueAt == null) {
            throw new IllegalArgumentException("scheduledOpenAt and dueAt are required");
        }
        if (!dueAt.isAfter(scheduledOpenAt)) {
            throw new IllegalArgumentException("dueAt must be after scheduledOpenAt");
        }
        if (scope == AttestationCampaignScope.DATASOURCE && datasourceId == null) {
            throw new IllegalAttestationScopeException(
                    "datasourceId is required for a DATASOURCE-scoped campaign");
        }
        if (scope == AttestationCampaignScope.ORGANIZATION && datasourceId != null) {
            throw new IllegalAttestationScopeException(
                    "datasourceId must be null for an ORGANIZATION-scoped campaign");
        }
    }
}
