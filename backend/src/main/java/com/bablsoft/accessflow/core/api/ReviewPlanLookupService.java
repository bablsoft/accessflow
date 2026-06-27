package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the {@link ReviewPlanSnapshot} attached to a datasource. Used by the workflow module
 * during AI-completion and review-decision handling without reaching into {@code core/internal}
 * JPA entities.
 */
public interface ReviewPlanLookupService {

    Optional<ReviewPlanSnapshot> findForDatasource(UUID datasourceId);

    /** Resolve a review plan directly by its id (e.g. an API connector's review_plan_id, AF-500). */
    Optional<ReviewPlanSnapshot> findById(UUID reviewPlanId);
}
