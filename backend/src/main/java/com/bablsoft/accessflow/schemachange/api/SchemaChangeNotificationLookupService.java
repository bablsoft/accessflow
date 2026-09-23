package com.bablsoft.accessflow.schemachange.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only projections for the notifications module (#882), so it can render schema-change
 * notifications and resolve their recipients without reaching into {@code schemachange.internal}.
 * Modelled on {@code deploygov.api.DeploymentNotificationLookupService}. Active-user filtering is
 * left to the caller — the id lists returned here are membership facts, not delivery decisions.
 */
public interface SchemaChangeNotificationLookupService {

    /** One promotion with the names its notification renders, or empty when it no longer exists. */
    Optional<SchemaChangePromotionNotificationView> findPromotion(UUID promotionId);

    /**
     * The users to alert that the promotion awaits review. The promotion's request group is
     * reviewed under the target datasource's plan with a single approval stage, so this mirrors
     * the group's own approver set: every approver rule of the plan (user ids, plus holders of a
     * named role) unioned with the datasource's reviewer assignments, falling back to the
     * {@code REVIEW_OVERRIDE} holders — the only users who can act on a group nobody is named for.
     * The promoter is always excluded.
     */
    List<UUID> findEligibleReviewerUserIds(UUID promotionId);

    /** The pipeline and environment names for a drift notification, or empty when either is gone. */
    Optional<SchemaDriftNotificationView> findDriftTarget(UUID organizationId, UUID pipelineId,
                                                          UUID environmentId);
}
