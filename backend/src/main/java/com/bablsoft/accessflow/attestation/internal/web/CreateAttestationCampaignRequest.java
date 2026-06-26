package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;
import com.bablsoft.accessflow.attestation.api.CreateAttestationCampaignCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request body for {@code POST /api/v1/admin/attestation-campaigns}. */
public record CreateAttestationCampaignRequest(
        @NotBlank(message = "{validation.attestation.name.required}")
        @Size(min = 3, max = 100, message = "{validation.attestation.name.size}")
        String name,

        @Size(max = 2000, message = "{validation.attestation.description.max}")
        String description,

        @NotNull(message = "{validation.attestation.scope.required}")
        AttestationCampaignScope scope,

        UUID datasourceId,

        AttestationPendingDefault pendingDefault,

        @NotNull(message = "{validation.attestation.scheduled_open.required}")
        Instant scheduledOpenAt,

        @NotNull(message = "{validation.attestation.due.required}")
        Instant dueAt) {

    @AssertTrue(message = "{validation.attestation.due_after_open}")
    public boolean isDueAfterScheduledOpen() {
        return scheduledOpenAt == null || dueAt == null || dueAt.isAfter(scheduledOpenAt);
    }

    @AssertTrue(message = "{validation.attestation.datasource_scope}")
    public boolean isDatasourceScopeConsistent() {
        if (scope == null) {
            return true;
        }
        return scope == AttestationCampaignScope.DATASOURCE
                ? datasourceId != null
                : datasourceId == null;
    }

    public CreateAttestationCampaignCommand toCommand(UUID organizationId, UUID createdBy) {
        return new CreateAttestationCampaignCommand(organizationId, createdBy, name, description,
                scope, datasourceId, pendingDefault, scheduledOpenAt, dueAt);
    }
}
