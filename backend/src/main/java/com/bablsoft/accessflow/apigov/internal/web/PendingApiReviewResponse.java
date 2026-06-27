package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiReviewService;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingApiReviewResponse(
        UUID apiRequestId, UUID connectorId, String connectorName, UUID submittedByUserId, String verb,
        String requestPath, boolean write, String justification, UUID aiAnalysisId, RiskLevel aiRiskLevel,
        Integer aiRiskScore, String aiSummary, int currentStage, Instant createdAt) {

    static PendingApiReviewResponse from(ApiReviewService.PendingApiReview p) {
        return new PendingApiReviewResponse(p.apiRequestId(), p.connectorId(), p.connectorName(),
                p.submittedByUserId(), p.verb(), p.requestPath(), p.write(), p.justification(),
                p.aiAnalysisId(), p.aiRiskLevel(), p.aiRiskScore(), p.aiSummary(), p.currentStage(),
                p.createdAt());
    }

    record Page(List<PendingApiReviewResponse> content, int page, int size, long totalElements,
                int totalPages) {
        static Page from(PageResponse<ApiReviewService.PendingApiReview> page) {
            return new Page(page.content().stream().map(PendingApiReviewResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }
}
