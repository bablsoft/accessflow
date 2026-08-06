package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.ApprovalPredictionProperties;
import com.bablsoft.accessflow.core.api.ApprovalOutcomeHistoryLookupService;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Objects;

/**
 * The serving-side adapter {@link ApprovalFeatureInput} anticipates (issue AF-651): turns one live
 * query request's persisted rows into the schema-v1 feature vector, so training and serving share
 * {@link ApprovalFeatureExtractor} and can never disagree on an encoding.
 *
 * <p><strong>Why {@code findDetailById} rather than the per-aggregate lookups.</strong> The training
 * query joins {@code ai_analyses} and {@code query_estimates} through the query's
 * {@code ai_analysis_id} / {@code query_estimate_id} back-pointers, so it reads the query's
 * <em>current</em> analysis rather than a superseded reanalysis
 * ({@code QueryRequestRepository.findApprovalOutcomeSampleRows}).
 * {@code QueryRequestLookupService.findDetailById} resolves both the same way;
 * {@code AiAnalysisLookupService.findByQueryRequestId} resolves by foreign key instead. Paying for
 * the slightly wider detail view buys exact train/serve parity.
 */
@Component
@RequiredArgsConstructor
class ApprovalFeatureLoader {

    private static final Logger log = LoggerFactory.getLogger(ApprovalFeatureLoader.class);

    private final QueryRequestLookupService queryRequestLookupService;
    private final ApprovalOutcomeHistoryLookupService approvalOutcomeHistoryLookupService;
    private final ApprovalFeatureExtractor featureExtractor;
    private final ApprovalPredictionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Builds the schema-v1 vector for an already-loaded query snapshot, or {@code null} when the
     * query's detail row has since disappeared (concurrent delete).
     *
     * <p>The historical rate counts are passed through unadjusted: the query being scored is not yet
     * decided, so it is outside the counted population and cannot leak its own outcome. The
     * {@code since} window matches the one the training run uses, so the feature is drawn from the
     * same population at train and at serve time.
     */
    ApprovalFeatureVector load(QueryRequestSnapshot query) {
        Objects.requireNonNull(query, "query");
        var detail = queryRequestLookupService
                .findDetailById(query.id(), query.organizationId())
                .orElse(null);
        if (detail == null) {
            log.warn("Approval prediction skipped: no detail view for query request {}", query.id());
            return null;
        }
        var since = clock.instant().minus(properties.trainingLookback());
        var submitterCounts = approvalOutcomeHistoryLookupService.submitterCounts(
                query.organizationId(), query.submittedByUserId(), since);
        var datasourceCounts = approvalOutcomeHistoryLookupService.datasourceCounts(
                query.organizationId(), query.datasourceId(), since);
        return featureExtractor.extract(toInput(query, detail), submitterCounts, datasourceCounts);
    }

    /**
     * Mirrors {@code DefaultApprovalOutcomeHistoryLookupService.toSample} field for field — the
     * missing-value derivations and the null-out of a missing block's payload have to agree with
     * training exactly, or a feature means something different at serving time.
     */
    private ApprovalFeatureInput toInput(QueryRequestSnapshot query, QueryDetailView detail) {
        var ai = detail.aiAnalysis();
        var estimate = detail.costEstimate();
        boolean aiMissing = ai == null || ai.failed();
        boolean estimateMissing = estimate == null || !estimate.supported() || estimate.failed();
        return new ApprovalFeatureInput(
                query.queryType(),
                query.transactional(),
                detail.createdAt(),
                aiMissing ? null : ai.riskScore(),
                aiMissing ? null : ai.riskLevel(),
                aiMissing ? null : countIssues(ai.issuesJson()),
                aiMissing,
                estimateMissing ? null : estimate.estimatedRows(),
                estimateMissing ? null : estimate.affectedRowCount(),
                estimateMissing ? null : estimate.estimatedCost(),
                estimateMissing ? null : estimate.scanType(),
                estimateMissing);
    }

    /** Same rule as training: absent or unparseable {@code issues} JSON counts as zero issues. */
    private int countIssues(String issuesJson) {
        if (issuesJson == null) {
            return 0;
        }
        try {
            return objectMapper.readTree(issuesJson).size();
        } catch (RuntimeException e) {
            log.debug("Unparseable ai_analyses.issues JSON, counting as 0 issues", e);
            return 0;
        }
    }
}
