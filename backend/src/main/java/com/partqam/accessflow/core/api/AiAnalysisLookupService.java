package com.partqam.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only facade for fetching the AI analysis attached to a query request without
 * crossing into {@code core/internal} JPA entities.
 */
public interface AiAnalysisLookupService {

    Optional<AiAnalysisSummaryView> findByQueryRequestId(UUID queryRequestId);
}
