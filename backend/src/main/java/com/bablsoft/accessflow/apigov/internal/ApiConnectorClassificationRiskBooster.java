package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorClassificationTagRepository;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministically raises an {@link AiAnalysisResult}'s risk when the analyzed API call references a
 * data-classified operation (AF-518) — the apigov mirror of {@code ai.internal.ClassificationRiskBooster}.
 * The bump is the strongest weight among the connector's classifications that apply to the call's
 * operation (operation-scoped tags whose operation matches, plus connector-level tags). The risk
 * level is recomputed from the bumped score by quartile thresholds and can only rise. Fully fail-safe
 * — any lookup error yields no bump and never blocks analysis.
 */
@Component
@RequiredArgsConstructor
class ApiConnectorClassificationRiskBooster {

    private static final Logger log = LoggerFactory.getLogger(ApiConnectorClassificationRiskBooster.class);
    private static final Map<DataClassification, Integer> WEIGHTS = new EnumMap<>(DataClassification.class);

    static {
        WEIGHTS.put(DataClassification.PII, 15);
        WEIGHTS.put(DataClassification.PCI, 30);
        WEIGHTS.put(DataClassification.PHI, 30);
        WEIGHTS.put(DataClassification.GDPR, 15);
        WEIGHTS.put(DataClassification.FINANCIAL, 20);
        WEIGHTS.put(DataClassification.SENSITIVE, 10);
    }

    private final ApiConnectorClassificationTagRepository tagRepository;

    /**
     * Returns {@code result} with score/level raised by the strongest classification weight applying
     * to {@code operationId} on {@code connectorId}; unchanged when no tag applies or on any error.
     */
    AiAnalysisResult boost(AiAnalysisResult result, UUID organizationId, UUID connectorId,
                           String operationId) {
        if (result == null) {
            return null;
        }
        try {
            return boost(result, bumpFor(classificationsFor(organizationId, connectorId, operationId)));
        } catch (RuntimeException ex) {
            log.warn("Classification risk bump failed for connector {} (non-blocking): {}",
                    connectorId, ex.getMessage());
            return result;
        }
    }

    private Set<DataClassification> classificationsFor(UUID organizationId, UUID connectorId,
                                                       String operationId) {
        var applicable = new LinkedHashSet<DataClassification>();
        for (var tag : tagRepository.findAllByOrganizationIdAndConnectorId(organizationId, connectorId)) {
            if (tag.getOperationId() == null || Objects.equals(tag.getOperationId(), operationId)) {
                applicable.add(tag.getClassification());
            }
        }
        return applicable;
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
                result.optimizations(),
                result.modelResults());
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
