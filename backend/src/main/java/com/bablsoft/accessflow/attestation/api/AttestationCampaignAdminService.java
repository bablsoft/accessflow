package com.bablsoft.accessflow.attestation.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Admin-facing campaign management: create, list, inspect, manually open, and cancel attestation
 * campaigns. All reads and mutations are scoped to the caller's organization.
 */
public interface AttestationCampaignAdminService {

    PageResponse<AttestationCampaignView> list(UUID organizationId,
                                               AttestationCampaignStatus statusFilter,
                                               PageRequest pageRequest);

    AttestationCampaignView get(UUID campaignId, UUID organizationId);

    PageResponse<AttestationItemView> listItems(UUID campaignId, UUID organizationId,
                                                PageRequest pageRequest);

    AttestationCampaignView create(CreateAttestationCampaignCommand command);

    /** Opens a SCHEDULED campaign immediately (snapshotting grants); idempotent if already OPEN. */
    AttestationCampaignView openNow(UUID campaignId, UUID organizationId);

    /** Cancels a SCHEDULED campaign. Throws when the campaign is OPEN, CLOSED, or already CANCELLED. */
    void cancel(UUID campaignId, UUID organizationId);
}
