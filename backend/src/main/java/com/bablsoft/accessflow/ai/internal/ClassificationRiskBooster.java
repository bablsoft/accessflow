package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Deterministically raises an {@link AiAnalysisResult}'s risk when the analyzed query references a
 * data-classified object (AF-447). The bump is the strongest weight among the referenced
 * classifications, added to the LLM-returned score (clamped to 100). The risk level is recomputed
 * from the bumped score by quartile thresholds and can only rise — it never drops below the LLM's
 * own verdict. The weights live here (not in {@code core}) because risk weighting is an
 * {@code ai}-module policy, distinct from the masking/review defaults the {@code core} module owns.
 */
final class ClassificationRiskBooster {

    private static final Map<DataClassification, Integer> WEIGHTS = new EnumMap<>(DataClassification.class);

    static {
        WEIGHTS.put(DataClassification.PII, 15);
        WEIGHTS.put(DataClassification.PCI, 30);
        WEIGHTS.put(DataClassification.PHI, 30);
        WEIGHTS.put(DataClassification.GDPR, 15);
        WEIGHTS.put(DataClassification.FINANCIAL, 20);
        WEIGHTS.put(DataClassification.SENSITIVE, 10);
    }

    private ClassificationRiskBooster() {
    }

    /** Strongest risk weight among the given classifications; 0 when none apply. */
    static int bumpFor(Collection<DataClassification> classifications) {
        var max = 0;
        if (classifications != null) {
            for (var classification : classifications) {
                var weight = WEIGHTS.getOrDefault(classification, 0);
                if (weight > max) {
                    max = weight;
                }
            }
        }
        return max;
    }

    /** Returns a copy of {@code result} with score/level raised by {@code bump}; unchanged when ≤ 0. */
    static AiAnalysisResult boost(AiAnalysisResult result, int bump) {
        if (result == null || bump <= 0) {
            return result;
        }
        var newScore = Math.min(100, result.riskScore() + bump);
        var newLevel = higher(result.riskLevel(), levelFromScore(newScore));
        return new AiAnalysisResult(
                newScore,
                newLevel,
                result.summary(),
                result.issues(),
                result.missingIndexesDetected(),
                result.affectsRowEstimate(),
                result.aiProvider(),
                result.aiModel(),
                result.promptTokens(),
                result.completionTokens(),
                result.optimizations());
    }

    static RiskLevel levelFromScore(int score) {
        if (score < 25) {
            return RiskLevel.LOW;
        }
        if (score < 50) {
            return RiskLevel.MEDIUM;
        }
        if (score < 75) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.CRITICAL;
    }

    static RiskLevel higher(RiskLevel a, RiskLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
