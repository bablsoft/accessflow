package com.bablsoft.accessflow.attestation.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Job-facing campaign lifecycle: find campaigns due to open/close and drive the transitions. The
 * open path snapshots current grants into items and publishes the opened event; the close path
 * applies the pending-default to still-PENDING items. Both are idempotent.
 */
public interface AttestationLifecycleService {

    List<UUID> findCampaignIdsDueToOpen(Instant now);

    List<UUID> findCampaignIdsDueToClose(Instant now);

    /** Opens a SCHEDULED campaign and snapshots grants. Returns {@code false} if no longer SCHEDULED. */
    boolean openCampaign(UUID campaignId);

    /** Closes an OPEN campaign and applies the pending-default. Returns {@code false} if not OPEN. */
    boolean closeCampaign(UUID campaignId);
}
