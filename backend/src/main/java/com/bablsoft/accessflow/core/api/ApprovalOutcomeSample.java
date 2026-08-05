package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One historical, human-decided query request used as a training sample by the approval-outcome
 * predictor (issue AF-645). Returned newest-first by
 * {@link ApprovalOutcomeHistoryLookupService#findDecidedSamples}.
 *
 * <p>{@code approved} is the label: {@code true} when the query reached {@code APPROVED} /
 * {@code EXECUTED} through human review, {@code false} for {@code REJECTED} / {@code TIMED_OUT}.
 *
 * <p>{@code aiMissing} is {@code true} when the query has no linked {@code ai_analyses} row or the
 * analysis failed — a failed row carries a placeholder risk score that must never be treated as a
 * real signal. When set, {@code aiRiskScore}, {@code aiRiskLevel} and {@code aiIssueCount} are
 * {@code null}. Likewise {@code estimateMissing} is {@code true} when there is no linked
 * {@code query_estimates} row, the estimate failed, or the engine does not support estimation;
 * when set, {@code estimatedRows}, {@code affectedRowCount}, {@code estimatedCost} and
 * {@code scanType} are {@code null}.
 */
public record ApprovalOutcomeSample(
        UUID queryRequestId,
        QueryType queryType,
        boolean transactional,
        Instant createdAt,
        UUID submitterId,
        UUID datasourceId,
        Integer aiRiskScore,
        RiskLevel aiRiskLevel,
        Integer aiIssueCount,
        boolean aiMissing,
        Long estimatedRows,
        Long affectedRowCount,
        Double estimatedCost,
        String scanType,
        boolean estimateMissing,
        boolean approved) {
}
