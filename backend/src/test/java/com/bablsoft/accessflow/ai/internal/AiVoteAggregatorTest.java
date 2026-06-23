package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiIssue;
import com.bablsoft.accessflow.ai.api.OptimizationSuggestion;
import com.bablsoft.accessflow.ai.api.OptimizationType;
import com.bablsoft.accessflow.ai.internal.AiVoteAggregator.WeightedResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.VotingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiVoteAggregatorTest {

    private static AiAnalysisResult result(int score, RiskLevel level, String summary,
                                           List<AiIssue> issues, boolean missingIndexes, Long affects,
                                           List<OptimizationSuggestion> optimizations) {
        return new AiAnalysisResult(score, level, summary, issues, missingIndexes, affects,
                AiProviderType.OPENAI, "m", 0, 0, optimizations);
    }

    private static AiAnalysisResult simple(int score, RiskLevel level, String summary) {
        return result(score, level, summary, List.of(), false, null, List.of());
    }

    @Test
    void weightedAverageComputesWeightedMeanAndLevelFromScore() {
        var members = List.of(
                new WeightedResult(simple(40, RiskLevel.MEDIUM, "low one"), 1.0),
                new WeightedResult(simple(80, RiskLevel.CRITICAL, "high one"), 3.0));

        var verdict = AiVoteAggregator.aggregate(members, VotingStrategy.WEIGHTED_AVERAGE);

        // (40*1 + 80*3) / 4 = 70
        assertThat(verdict.riskScore()).isEqualTo(70);
        assertThat(verdict.riskLevel()).isEqualTo(RiskLevel.HIGH); // 50..74
        // summary comes from the highest-scoring member
        assertThat(verdict.summary()).isEqualTo("high one");
    }

    @Test
    void maxRiskTakesHighestScoringMemberVerbatim() {
        var members = List.of(
                new WeightedResult(simple(40, RiskLevel.MEDIUM, "low one"), 5.0),
                new WeightedResult(simple(90, RiskLevel.CRITICAL, "high one"), 1.0));

        var verdict = AiVoteAggregator.aggregate(members, VotingStrategy.MAX_RISK);

        assertThat(verdict.riskScore()).isEqualTo(90);
        assertThat(verdict.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(verdict.summary()).isEqualTo("high one");
    }

    @Test
    void majorityPicksMostWeightedLevelAndAveragesItsMembers() {
        var members = List.of(
                new WeightedResult(simple(20, RiskLevel.LOW, "a"), 1.0),
                new WeightedResult(simple(30, RiskLevel.LOW, "b"), 1.0),
                new WeightedResult(simple(90, RiskLevel.CRITICAL, "c"), 1.0));

        var verdict = AiVoteAggregator.aggregate(members, VotingStrategy.MAJORITY);

        // LOW wins (weight 2 > 1). Score = avg of the two LOW members = 25.
        assertThat(verdict.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(verdict.riskScore()).isEqualTo(25);
    }

    @Test
    void majorityTieBreaksTowardHigherRisk() {
        var members = List.of(
                new WeightedResult(simple(20, RiskLevel.LOW, "a"), 1.0),
                new WeightedResult(simple(90, RiskLevel.CRITICAL, "b"), 1.0));

        var verdict = AiVoteAggregator.aggregate(members, VotingStrategy.MAJORITY);

        assertThat(verdict.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(verdict.riskScore()).isEqualTo(90);
    }

    @Test
    void mergesIssuesAndOptimizationsDedupedAndOrsMissingIndexes() {
        var issueA = new AiIssue(RiskLevel.HIGH, "PERF", "slow", "fix");
        var issueDup = new AiIssue(RiskLevel.HIGH, "PERF", "slow", "fix");
        var issueB = new AiIssue(RiskLevel.LOW, "SEC", "leak", "patch");
        var optA = new OptimizationSuggestion(OptimizationType.INDEX, "idx", "why", "CREATE INDEX");
        var optDup = new OptimizationSuggestion(OptimizationType.INDEX, "idx", "why2", "CREATE INDEX 2");

        var members = List.of(
                new WeightedResult(result(60, RiskLevel.HIGH, "x", List.of(issueA), true, 100L,
                        List.of(optA)), 1.0),
                new WeightedResult(result(40, RiskLevel.MEDIUM, "y", List.of(issueDup, issueB), false,
                        500L, List.of(optDup)), 1.0));

        var verdict = AiVoteAggregator.aggregate(members, VotingStrategy.WEIGHTED_AVERAGE);

        assertThat(verdict.issues()).containsExactly(issueA, issueB); // dup collapsed
        assertThat(verdict.optimizations()).hasSize(1); // same (type,title) collapsed
        assertThat(verdict.missingIndexesDetected()).isTrue(); // OR
        assertThat(verdict.affectsRowEstimate()).isEqualTo(500L); // max
    }

    @Test
    void singleMemberReturnsThatMembersVerdict() {
        var members = List.of(new WeightedResult(simple(33, RiskLevel.MEDIUM, "only"), 2.0));

        var verdict = AiVoteAggregator.aggregate(members, VotingStrategy.WEIGHTED_AVERAGE);

        assertThat(verdict.riskScore()).isEqualTo(33);
        assertThat(verdict.summary()).isEqualTo("only");
    }

    @Test
    void emptyMembersRejected() {
        assertThatThrownBy(() -> AiVoteAggregator.aggregate(List.of(), VotingStrategy.MAX_RISK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
