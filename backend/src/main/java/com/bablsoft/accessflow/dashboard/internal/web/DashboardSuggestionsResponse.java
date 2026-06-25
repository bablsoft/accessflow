package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.ai.api.OptimizationType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API envelope for the AI optimization-suggestion backlog (AF-498). */
public record DashboardSuggestionsResponse(List<SuggestionResponse> suggestions) {

    public static DashboardSuggestionsResponse from(List<DashboardSuggestion> suggestions) {
        return new DashboardSuggestionsResponse(suggestions.stream()
                .map(SuggestionResponse::from).toList());
    }

    public record SuggestionResponse(
            String id,
            UUID aiAnalysisId,
            int index,
            UUID queryRequestId,
            UUID datasourceId,
            String datasourceName,
            DbType dbType,
            RiskLevel riskLevel,
            OptimizationType type,
            String title,
            String rationale,
            String sql,
            Instant analyzedAt) {

        static SuggestionResponse from(DashboardSuggestion s) {
            return new SuggestionResponse(s.id(), s.aiAnalysisId(), s.index(), s.queryRequestId(),
                    s.datasourceId(), s.datasourceName(), s.dbType(), s.riskLevel(), s.type(),
                    s.title(), s.rationale(), s.sql(), s.analyzedAt());
        }
    }
}
