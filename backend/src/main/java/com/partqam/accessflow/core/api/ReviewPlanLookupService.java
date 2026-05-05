package com.partqam.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the {@link ReviewPlanSnapshot} attached to a datasource. Used by the workflow module
 * during AI-completion and review-decision handling without reaching into {@code core/internal}
 * JPA entities.
 */
public interface ReviewPlanLookupService {

    Optional<ReviewPlanSnapshot> findForDatasource(UUID datasourceId);
}
