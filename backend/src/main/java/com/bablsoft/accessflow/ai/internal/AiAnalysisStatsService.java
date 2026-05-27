package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisStatsLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsRaw;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregates {@code ai_analyses} statistics for the admin dashboard. The controller layer
 * delegates here so it stays parameter-binding-only (per CLAUDE.md controller layering rule).
 *
 * <p>Concrete @Service rather than interface + Default impl because the only consumer is the
 * sibling controller inside this module.
 */
@Service
@RequiredArgsConstructor
public class AiAnalysisStatsService {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final AiAnalysisStatsLookupService lookupService;

    public AiAnalysisStatsRaw stats(UUID organizationId, Instant from, Instant to, UUID datasourceId) {
        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_WINDOW);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new BadAiAnalysisStatsQueryException("error.ai_analysis_stats.invalid_range");
        }
        return lookupService.query(organizationId, resolvedFrom, resolvedTo, datasourceId);
    }
}
