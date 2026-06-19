package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationRiskBoosterTest {

    private AiAnalysisResult result(int score, RiskLevel level) {
        return new AiAnalysisResult(score, level, "summary", List.of(), false, null,
                AiProviderType.ANTHROPIC, "model-x", 1, 1, List.of());
    }

    @Test
    void bumpForReturnsStrongestWeight() {
        assertThat(ClassificationRiskBooster.bumpFor(
                Set.of(DataClassification.SENSITIVE, DataClassification.PCI))).isEqualTo(30);
        assertThat(ClassificationRiskBooster.bumpFor(Set.of(DataClassification.PII))).isEqualTo(15);
    }

    @Test
    void bumpForEmptyOrNullIsZero() {
        assertThat(ClassificationRiskBooster.bumpFor(Set.of())).isZero();
        assertThat(ClassificationRiskBooster.bumpFor(null)).isZero();
    }

    @Test
    void boostRaisesScoreAndLevel() {
        var boosted = ClassificationRiskBooster.boost(result(60, RiskLevel.MEDIUM), 30);

        assertThat(boosted.riskScore()).isEqualTo(90);
        assertThat(boosted.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void boostClampsScoreAtHundred() {
        var boosted = ClassificationRiskBooster.boost(result(90, RiskLevel.HIGH), 30);

        assertThat(boosted.riskScore()).isEqualTo(100);
        assertThat(boosted.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void boostNeverLowersLevelBelowTheLlmVerdict() {
        // Bumped score 30 → MEDIUM by threshold, but LLM already said HIGH — level must not drop.
        var boosted = ClassificationRiskBooster.boost(result(5, RiskLevel.HIGH), 25);

        assertThat(boosted.riskScore()).isEqualTo(30);
        assertThat(boosted.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void boostWithZeroBumpReturnsResultUnchanged() {
        var original = result(40, RiskLevel.MEDIUM);

        assertThat(ClassificationRiskBooster.boost(original, 0)).isSameAs(original);
    }

    @Test
    void levelFromScoreUsesQuartiles() {
        assertThat(ClassificationRiskBooster.levelFromScore(0)).isEqualTo(RiskLevel.LOW);
        assertThat(ClassificationRiskBooster.levelFromScore(24)).isEqualTo(RiskLevel.LOW);
        assertThat(ClassificationRiskBooster.levelFromScore(25)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ClassificationRiskBooster.levelFromScore(49)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ClassificationRiskBooster.levelFromScore(50)).isEqualTo(RiskLevel.HIGH);
        assertThat(ClassificationRiskBooster.levelFromScore(74)).isEqualTo(RiskLevel.HIGH);
        assertThat(ClassificationRiskBooster.levelFromScore(75)).isEqualTo(RiskLevel.CRITICAL);
        assertThat(ClassificationRiskBooster.levelFromScore(100)).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void higherHandlesNulls() {
        assertThat(ClassificationRiskBooster.higher(null, RiskLevel.LOW)).isEqualTo(RiskLevel.LOW);
        assertThat(ClassificationRiskBooster.higher(RiskLevel.HIGH, null)).isEqualTo(RiskLevel.HIGH);
        assertThat(ClassificationRiskBooster.higher(RiskLevel.LOW, RiskLevel.CRITICAL))
                .isEqualTo(RiskLevel.CRITICAL);
    }
}
