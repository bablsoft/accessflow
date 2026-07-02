package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only facade for fetching the AI analysis attached to a query request without
 * crossing into {@code core/internal} JPA entities.
 */
public interface AiAnalysisLookupService {

    Optional<AiAnalysisSummaryView> findByQueryRequestId(UUID queryRequestId);

    /** Fetch an analysis directly by its id (e.g. an API request's analysis, AF-500). */
    Optional<AiAnalysisSummaryView> findById(UUID analysisId);

    /**
     * Fetch the full analysis detail (issues, optimizations, provider/model, token counts) by id —
     * used by the request-groups detail view to embed per-member analyses (AF-531).
     */
    Optional<QueryDetailView.AiAnalysisDetail> findDetailById(UUID analysisId);
}
