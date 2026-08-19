package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the enabled export policies applicable to one exporter on one datasource (#626).
 * Applies-to matching follows the row-security polarity: a policy with all three
 * {@code applies_to} lists empty applies to every exporter (no implicit ADMIN bypass); a
 * non-empty list narrows it by role name (case-insensitive), group id, or user id. Combining
 * the returned policies into an effective decision (most-restrictive-wins, classification
 * matching) is the caller's concern — the compliance module owns that step.
 */
public interface ExportPolicyResolutionService {

    List<ExportPolicyView> resolveApplicable(UUID organizationId, UUID datasourceId,
                                             UUID requesterUserId);
}
