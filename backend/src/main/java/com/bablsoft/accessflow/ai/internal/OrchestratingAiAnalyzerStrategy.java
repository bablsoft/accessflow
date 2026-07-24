package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiModelResult;
import com.bablsoft.accessflow.ai.internal.AiVoteAggregator.WeightedResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.VotingStrategy;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs N member strategies in parallel on virtual threads and combines their results via a
 * {@link VotingStrategy} (AF-450). Member 0 is the primary {@code ai_config} model; the rest come
 * from {@code ai_config_model} rows. Each member is timed independently; the aggregate
 * {@link AiAnalysisResult} carries a per-member {@link AiModelResult} breakdown (success and failed
 * members alike) so cost / latency can be persisted per model. Tokens are summed across successful
 * members. When every member fails, the first failure is rethrown (so the async path records a
 * sentinel). With a single member it degenerates to that member's result plus a one-entry breakdown.
 *
 * <p>{@code generateSql} is not voted — it delegates to the primary member only.
 */
class OrchestratingAiAnalyzerStrategy implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(OrchestratingAiAnalyzerStrategy.class);

    /** One orchestration member: its strategy (provider call + tracing), provider/model and weight. */
    record Member(AiAnalyzerStrategy strategy, AiProviderType provider, String model, double weight) {
    }

    private final List<Member> members;
    private final VotingStrategy votingStrategy;
    private final Clock clock;

    OrchestratingAiAnalyzerStrategy(List<Member> members, VotingStrategy votingStrategy, Clock clock) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("orchestrator requires at least one member");
        }
        this.members = List.copyOf(members);
        this.votingStrategy = votingStrategy == null ? VotingStrategy.WEIGHTED_AVERAGE : votingStrategy;
        this.clock = clock;
    }

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                                    String costEstimateContext, String language, UUID aiConfigId) {
        var outcomes = invokeAll(sql, dbType, schemaContext, costEstimateContext, language, aiConfigId);

        var successes = new ArrayList<WeightedResult>();
        var breakdown = new ArrayList<AiModelResult>(outcomes.size());
        RuntimeException firstFailure = null;
        int promptTokens = 0;
        int completionTokens = 0;

        for (var outcome : outcomes) {
            breakdown.add(outcome.toModelResult());
            if (outcome.failure() == null) {
                successes.add(new WeightedResult(outcome.result(), outcome.member().weight()));
                promptTokens += outcome.result().promptTokens();
                completionTokens += outcome.result().completionTokens();
            } else if (firstFailure == null) {
                firstFailure = outcome.failure();
            }
        }

        if (successes.isEmpty()) {
            // Every member failed — rethrow the first failure so the caller records a sentinel.
            throw firstFailure;
        }

        var verdict = AiVoteAggregator.aggregate(successes, votingStrategy);
        var primary = members.get(0);
        return new AiAnalysisResult(
                verdict.riskScore(),
                verdict.riskLevel(),
                verdict.summary(),
                verdict.issues(),
                verdict.missingIndexesDetected(),
                verdict.affectsRowEstimate(),
                primary.provider(),
                primary.model(),
                promptTokens,
                completionTokens,
                verdict.optimizations(),
                breakdown);
    }

    @Override
    public GeneratedSqlResult generateSql(String prompt, DbType dbType, String schemaContext,
                                          String language, UUID aiConfigId) {
        return members.get(0).strategy().generateSql(prompt, dbType, schemaContext, language, aiConfigId);
    }

    private List<MemberOutcome> invokeAll(String sql, DbType dbType, String schemaContext,
                                          String costEstimateContext, String language, UUID aiConfigId) {
        if (members.size() == 1) {
            return List.of(runMember(members.get(0), sql, dbType, schemaContext, costEstimateContext,
                    language, aiConfigId));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<MemberOutcome>>(members.size());
            for (var member : members) {
                Callable<MemberOutcome> task =
                        () -> runMember(member, sql, dbType, schemaContext, costEstimateContext,
                                language, aiConfigId);
                futures.add(executor.submit(task));
            }
            var outcomes = new ArrayList<MemberOutcome>(members.size());
            for (var future : futures) {
                outcomes.add(joinQuietly(future));
            }
            return outcomes;
        }
    }

    private MemberOutcome runMember(Member member, String sql, DbType dbType, String schemaContext,
                                    String costEstimateContext, String language, UUID aiConfigId) {
        var start = clock.instant();
        try {
            var result = member.strategy().analyze(sql, dbType, schemaContext, costEstimateContext,
                    language, aiConfigId);
            return MemberOutcome.success(member, result, elapsedMs(start));
        } catch (RuntimeException e) {
            log.warn("Orchestration member {}/{} failed: {}", member.provider(), member.model(),
                    e.getMessage());
            return MemberOutcome.failure(member, e, elapsedMs(start));
        }
    }

    private MemberOutcome joinQuietly(Future<MemberOutcome> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            // runMember never throws — but guard against an unexpected task error.
            var cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while orchestrating AI analysis", e);
        }
    }

    private long elapsedMs(java.time.Instant start) {
        return Math.max(0, Duration.between(start, clock.instant()).toMillis());
    }

    private record MemberOutcome(Member member, AiAnalysisResult result, long latencyMs,
                                 RuntimeException failure) {

        static MemberOutcome success(Member member, AiAnalysisResult result, long latencyMs) {
            return new MemberOutcome(member, result, latencyMs, null);
        }

        static MemberOutcome failure(Member member, RuntimeException failure, long latencyMs) {
            return new MemberOutcome(member, null, latencyMs, failure);
        }

        AiModelResult toModelResult() {
            if (failure != null) {
                return new AiModelResult(member.provider(), member.model(), null, null,
                        member.weight(), 0, 0, latencyMs, true, failure.getMessage());
            }
            return new AiModelResult(member.provider(), member.model(), result.riskScore(),
                    result.riskLevel(), member.weight(), result.promptTokens(),
                    result.completionTokens(), latencyMs, false, null);
        }
    }
}
