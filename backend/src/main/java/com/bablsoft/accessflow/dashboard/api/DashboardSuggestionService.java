package com.bablsoft.accessflow.dashboard.api;

import java.util.List;
import java.util.UUID;

/**
 * The current user's AI optimization-suggestion backlog (AF-498), derived from their queries'
 * {@code ai_analyses.optimizations[]} and joined with per-item dismissal state persisted in
 * {@code dashboard_suggestion_state}. Self-scoped throughout.
 */
public interface DashboardSuggestionService {

    /** The user's OPEN suggestion backlog, newest analysis first. */
    List<DashboardSuggestion> listOpen(UUID organizationId, UUID userId);

    /** Number of OPEN suggestions in the backlog (cheap count for the summary widget). */
    long countOpen(UUID organizationId, UUID userId);

    /**
     * Marks the suggestion {@code id} ({@code {aiAnalysisId}:{index}}) as DISMISSED for the user.
     * Idempotent. Throws {@link InvalidSuggestionIdException} when {@code id} is malformed or the
     * referenced analysis is not the user's / has no such item.
     */
    void dismiss(UUID organizationId, UUID userId, String id);
}
