package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.Objects;

/**
 * The engine-neutral input to {@link ApprovalFeatureExtractor} (issue AF-650) — everything about a
 * query request that schema v1 turns into a feature, and nothing else.
 *
 * <p>It exists so training and serving share one extraction code path with two thin adapters:
 * training maps a historical {@link ApprovalOutcomeSample} through {@link #from}, serving builds
 * one from the live query's persisted rows. Deliberately carries neither the {@code approved}
 * label nor any identity column, so a serving-time input cannot accidentally acquire a fabricated
 * label.
 *
 * <p>Missing-value contract, mirroring {@link ApprovalOutcomeSample}: when {@code aiMissing} is
 * {@code true}, {@code aiRiskScore} / {@code aiRiskLevel} / {@code aiIssueCount} are {@code null};
 * when {@code estimateMissing} is {@code true}, {@code estimatedRows} / {@code affectedRowCount} /
 * {@code estimatedCost} / {@code scanType} are {@code null}. The extractor tolerates any
 * combination regardless — every field is independently null-safe.
 */
record ApprovalFeatureInput(
        QueryType queryType,
        boolean transactional,
        Instant createdAt,
        Integer aiRiskScore,
        RiskLevel aiRiskLevel,
        Integer aiIssueCount,
        boolean aiMissing,
        Long estimatedRows,
        Long affectedRowCount,
        Double estimatedCost,
        String scanType,
        boolean estimateMissing) {

    /** Adapts a historical training sample, dropping its label and identity columns. */
    static ApprovalFeatureInput from(ApprovalOutcomeSample sample) {
        Objects.requireNonNull(sample, "sample");
        return new ApprovalFeatureInput(
                sample.queryType(),
                sample.transactional(),
                sample.createdAt(),
                sample.aiRiskScore(),
                sample.aiRiskLevel(),
                sample.aiIssueCount(),
                sample.aiMissing(),
                sample.estimatedRows(),
                sample.affectedRowCount(),
                sample.estimatedCost(),
                sample.scanType(),
                sample.estimateMissing());
    }
}
