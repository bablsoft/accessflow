package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the {@link ConditionContext} routing policies are evaluated against.
 *
 * <p>There is deliberately one builder, used by both the live state machine and the policy
 * simulator (issue AF-630). A simulator with its own copy would drift from production the first
 * time a signal changed, and the whole value of a dry run is that it predicts what the live path
 * would actually do.
 *
 * <p>The two entry points differ only in <em>when</em> they claim to be evaluating.
 * {@link #forLiveQuery} stamps now; {@link #forHistoricalRow} stamps the query's own submission
 * instant, so {@code time_of_day} / {@code day_of_week} replay faithfully and
 * {@code time_since_last_approval} is measured from the right moment rather than from today.
 */
@Component
@RequiredArgsConstructor
public class ConditionContextFactory {

    private static final Logger log = LoggerFactory.getLogger(ConditionContextFactory.class);

    private final QueryRequestLookupService queryRequestLookupService;
    private final SqlParserService sqlParserService;
    private final UserQueryService userQueryService;
    private final UserGroupService userGroupService;
    private final BehaviorAnomalyLookupService behaviorAnomalyLookupService;
    private final QueryEstimateLookupService queryEstimateLookupService;

    /** The live routing path: signals as they stand right now, which is also when routing runs. */
    public ConditionContext forLiveQuery(QueryRequestSnapshot query, RiskLevel riskLevel,
                                         int riskScore, Clock clock) {
        var parsed = parse(query.id(), query.sqlText(), query.transactional());
        var estimate = estimateSignals(query.id());
        return new ConditionContext(query.queryType(), parsed.tables(), riskLevel, riskScore,
                roleName(query.submittedByUserId()), groupIds(query.submittedByUserId()),
                LocalDateTime.now(clock), parsed.hasWhere(), parsed.hasLimit(), parsed.transactional(),
                query.submittedIp(), query.submittedUserAgent(), query.ciCdOrigin(),
                minutesSinceLastApproval(query.organizationId(), query.submittedByUserId(),
                        query.datasourceId(), query.id(), clock.instant()),
                behaviorAnomalyLookupService.hasActiveAnomaly(query.organizationId(),
                        query.submittedByUserId(), query.datasourceId()),
                estimate.rows(), estimate.scanType());
    }

    /**
     * The replay path: the same signals, evaluated as of {@code row}'s submission instant.
     *
     * <p>Exactly two signals cannot be reconstructed and are approximated on purpose — the caller
     * reports them as caveats rather than hiding them. Role and group membership are read as they
     * are now, and {@code anomalyActive} is forced to {@code false} because the UBA signal is a
     * statement about today's open anomalies, not about the historical query. Both arms of a
     * simulation see the identical approximation, so the diff between them stays sound. Everything
     * else — including the AF-624 cost estimate — is replayed from what was persisted.
     */
    public ConditionContext forHistoricalRow(QueryCorpusRow row, ZoneId zone) {
        var parsed = parse(row.id(), row.sqlText(), row.transactional());
        var estimate = estimateSignals(row.id());
        return new ConditionContext(row.queryType(), parsed.tables(), row.aiRiskLevel(),
                row.aiRiskScore() != null ? row.aiRiskScore() : -1,
                roleName(row.submittedByUserId()), groupIds(row.submittedByUserId()),
                LocalDateTime.ofInstant(row.createdAt(), zone), parsed.hasWhere(), parsed.hasLimit(),
                parsed.transactional(), row.submittedIp(), row.submittedUserAgent(), row.ciCdOrigin(),
                minutesSinceLastApproval(row.organizationId(), row.submittedByUserId(),
                        row.datasourceId(), row.id(), row.createdAt()),
                false, estimate.rows(), estimate.scanType());
    }

    /**
     * AF-624 pre-flight estimate signals. The estimate pipeline runs independently of AI analysis,
     * so whatever is persisted for the query is the signal; absent / unsupported / failed rows
     * leave both fields null and the matching conditions fail closed.
     *
     * <p>The replay arm reads it too: unlike membership or the anomaly flag, the estimate is a
     * persisted per-query fact, so dropping it would make an {@code estimated_rows} policy simulate
     * as matching nothing — a false all-clear on a policy that would start firing once saved.
     */
    private EstimateSignals estimateSignals(UUID queryRequestId) {
        var estimate = queryEstimateLookupService.findByQueryRequestId(queryRequestId).orElse(null);
        if (estimate == null || estimate.failed()) {
            return new EstimateSignals(null, null);
        }
        var rows = estimate.affectedRowCount() != null
                ? estimate.affectedRowCount()
                : estimate.estimatedRows();
        return new EstimateSignals(rows, estimate.scanType());
    }

    private record EstimateSignals(Long rows, String scanType) {
    }

    private String roleName(UUID userId) {
        return userQueryService.findById(userId).map(UserView::roleName).orElse(null);
    }

    private Set<UUID> groupIds(UUID userId) {
        return Set.copyOf(userGroupService.findGroupIdsForUser(userId));
    }

    private Integer minutesSinceLastApproval(UUID organizationId, UUID userId, UUID datasourceId,
                                             UUID excludingQueryId, Instant asOf) {
        return queryRequestLookupService
                .findLastApprovalInstant(organizationId, userId, datasourceId, excludingQueryId)
                .filter(last -> !last.isAfter(asOf))
                .map(last -> (int) Math.max(0, Duration.between(last, asOf).toMinutes()))
                .orElse(null);
    }

    private ParsedSignals parse(UUID queryId, String sqlText, boolean transactional) {
        try {
            var parsed = sqlParserService.parse(sqlText);
            return new ParsedSignals(parsed.referencedTables(), parsed.hasWhereClause(),
                    parsed.hasLimitClause(), parsed.transactional());
        } catch (RuntimeException ex) {
            log.warn("Routing: failed to re-parse SQL for query {}; table/clause signals unavailable",
                    queryId);
            return new ParsedSignals(Set.of(), false, false, transactional);
        }
    }

    /** The four signals re-derived from the SQL text; unavailable ones fail closed. */
    private record ParsedSignals(Set<String> tables, boolean hasWhere, boolean hasLimit,
                                 boolean transactional) {
    }
}
