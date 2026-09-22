package com.bablsoft.accessflow.schemachange.api;

import java.util.List;
import java.util.UUID;

/**
 * Promotion of a change set along a pipeline's environment ladder (#878, epic #870; implemented in
 * #880). A promotion is refused — failing closed — unless every lower-ordered environment with a
 * datasource bound records an {@link SchemaChangePromotionStatus#APPLIED} promotion of the set,
 * the target is not frozen, and the promoting user holds DDL on the target datasource. The
 * accepted promotion runs as an ordered request group whose status is projected back onto it.
 */
public interface SchemaChangePromotionService {

    SchemaChangePromotionView promote(UUID organizationId, UUID actorId, UUID changeSetId,
                                      PromoteSchemaChangeSetCommand command);

    SchemaChangePromotionView get(UUID organizationId, UUID promotionId);

    List<SchemaChangePromotionView> listForChangeSet(UUID organizationId, UUID changeSetId);
}
