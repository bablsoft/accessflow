package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.ApprovalRateCounts;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns one query request into the schema-v1 {@link ApprovalFeatureVector} consumed by the
 * approval-outcome trainer and the serving path (issue AF-650). Stateless and dependency-free —
 * the same instance serves both callers, so training and serving can never disagree on an encoding.
 *
 * <p><strong>Missing-value policy.</strong> Every absent input contributes an explicit indicator
 * feature plus a zero fill; nothing is imputed and no value is ever {@code NaN} or infinite. That
 * covers {@code null} inputs, non-finite doubles read back from a {@code double precision} column,
 * and negative values that would otherwise make {@code log10} undefined.
 *
 * <p>The indicator features are redundant with their payloads by design — {@code ai_missing} is
 * normally {@code 1 - sum(risk_level_*)} and {@code estimate_missing} tracks the three
 * {@code *_log10} features being zero. Keeping both is what stops an absent input from reading as
 * a genuine zero, and the trainer's standardization plus L2 regularization absorbs the collinearity.
 *
 * <p><strong>Reference category.</strong> {@link QueryType#OTHER} and a {@code null} query type or
 * risk level leave their entire one-hot block at zero. Adding a constant to either enum is a
 * schema-v2 event — the one-hot indices are resolved by name from
 * {@link ApprovalFeatureVector#FEATURE_SCHEMA_V1}, so a new constant degrades to the reference
 * category rather than shifting positions.
 */
@Component
class ApprovalFeatureExtractor {

    /**
     * Business hours are fixed to UTC rather than read from the application {@code Clock} bean:
     * training and serving must bucket {@code off_hours} identically, and a deployment-time zone
     * change would otherwise silently invalidate every already-trained model.
     */
    private static final ZoneOffset FEATURE_ZONE = ZoneOffset.UTC;

    /** Inclusive first business hour — {@code 07:59:59Z} is off-hours, {@code 08:00:00Z} is not. */
    private static final int BUSINESS_HOURS_START = 8;

    /** Exclusive last business hour — {@code 17:59:59Z} is business hours, {@code 18:00:00Z} is not. */
    private static final int BUSINESS_HOURS_END = 18;

    /**
     * Normalized operation names that identify a full/sequential scan in the free-form,
     * engine-specific {@code query_estimates.scan_type} string — there is no enum for it.
     *
     * <p><strong>What actually lands in the column.</strong> {@code DefaultQueryCostEstimateService}
     * stores the <em>root</em> plan node's operation, descending exactly one level for
     * {@code INSERT}/{@code UPDATE}/{@code DELETE} to skip the modify node. It is not a search of
     * the whole tree. So the feature fires for a Postgres or Oracle {@code SELECT} whose root is
     * the scan, and for relational writes whose first child is; it stays 0 for shapes whose root is
     * something else — a MySQL {@code SELECT} roots at {@code query_block}, a SQL Server
     * {@code SELECT} at the statement text, Couchbase at {@code Sequence}, Neo4j at
     * {@code ProduceResults}, a Postgres aggregate at {@code Aggregate}. That is a limitation of
     * the upstream estimate (AF-624), not of this encoding, and it is symmetric between training
     * and serving.
     *
     * <p><strong>Why exact membership rather than a substring search.</strong> Because a SQL Server
     * {@code SELECT} puts user SQL in this column, a {@code contains} match would read a table
     * named {@code tablescan_log} as a sequential scan. Matching the whole normalized name cannot.
     * The trade is that an unrecognised engine variant scores 0 instead of 1 — the safe direction
     * for an advisory signal.
     *
     * <p>Deliberately absent, because they are index reads: {@code Index Scan},
     * {@code Index Only Scan}, {@code Bitmap Heap Scan}, {@code IXSCAN}, {@code IndexScan3},
     * {@code Index Seek}, {@code INDEX FULL SCAN}, {@code INDEX FAST FULL SCAN} and
     * {@code TABLE ACCESS BY INDEX ROWID}. SQL Server's {@code Clustered Index Scan} is absent too,
     * even though it does read a whole table — it is not distinguishable here from a bounded scan.
     */
    private static final Set<String> SEQ_SCAN_OPERATIONS = Set.of(
            "all",                          // MySQL / MariaDB access_type on a write's table node
            "seq scan",                     // PostgreSQL
            "parallel seq scan",            // PostgreSQL
            "table access full",            // Oracle
            "table access storage full",    // Oracle on Exadata
            "mat_view access full",         // Oracle materialized view
            "table scan",                   // SQL Server heap scan
            "tablescan",                    // Snowflake
            "collscan",                     // MongoDB
            "primaryscan",                  // Couchbase (version suffix stripped)
            "allnodesscan",                 // Neo4j
            "nodebylabelscan");             // Neo4j

    /** Collapses internal whitespace runs so {@code "Parallel  Seq Scan"} still matches. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** Strips Couchbase's operator version suffix — {@code PrimaryScan3} to {@code primaryscan}. */
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");

    /** Feature name to its frozen position, so a typo fails fast instead of writing out of range. */
    private static final Map<String, Integer> FEATURE_INDEX = featureIndex();

    private static final Map<RiskLevel, Integer> RISK_LEVEL_INDEX = oneHotIndices(
            RiskLevel.class, RiskLevel.values(), "risk_level_");

    private static final Map<QueryType, Integer> QUERY_TYPE_INDEX = oneHotIndices(
            QueryType.class, QueryType.values(), "query_type_");

    private static Map<String, Integer> featureIndex() {
        var indices = new HashMap<String, Integer>();
        var schema = ApprovalFeatureVector.FEATURE_SCHEMA_V1;
        for (int i = 0; i < schema.size(); i++) {
            indices.put(schema.get(i), i);
        }
        return Map.copyOf(indices);
    }

    private static <E extends Enum<E>> Map<E, Integer> oneHotIndices(
            Class<E> type, E[] constants, String prefix) {
        var indices = new EnumMap<E, Integer>(type);
        for (E constant : constants) {
            Integer index = FEATURE_INDEX.get(prefix + constant.name());
            if (index != null) {
                indices.put(constant, index);
            }
        }
        return indices;
    }

    /** Adapts a historical training sample; the label is not a feature and is ignored. */
    ApprovalFeatureVector extract(ApprovalOutcomeSample sample,
                                  ApprovalRateCounts submitterCounts,
                                  ApprovalRateCounts datasourceCounts) {
        return extract(ApprovalFeatureInput.from(sample), submitterCounts, datasourceCounts);
    }

    /**
     * Extracts the schema-v1 vector. Either rate-count argument may be {@code null}, which is
     * treated the same as no decided history at all.
     *
     * <p><strong>Caller contract at training time:</strong> the counts must exclude the sample
     * being featurized. {@code ApprovalOutcomeHistoryLookupService.submitterCounts} /
     * {@code datasourceCounts} aggregate the whole decided population, so passing them unadjusted
     * makes {@code submitter_approval_rate} a partial function of the sample's own label — target
     * leakage that inflates holdout AUC and can push a model that does not generalise past the
     * quality gate. Serving is unaffected: the query being scored is not yet decided.
     */
    ApprovalFeatureVector extract(ApprovalFeatureInput input,
                                  ApprovalRateCounts submitterCounts,
                                  ApprovalRateCounts datasourceCounts) {
        Objects.requireNonNull(input, "input");
        var values = new double[ApprovalFeatureVector.FEATURE_SCHEMA_V1.size()];

        set(values, "risk_score", input.aiRiskScore() == null ? 0.0 : input.aiRiskScore() / 100.0);
        setOneHot(values, RISK_LEVEL_INDEX, input.aiRiskLevel());
        set(values, "issue_count", input.aiIssueCount() == null ? 0.0 : input.aiIssueCount());
        set(values, "ai_missing", flag(input.aiMissing()));

        set(values, "estimated_rows_log10", log10p(input.estimatedRows()));
        set(values, "affected_row_count_log10", log10p(input.affectedRowCount()));
        set(values, "estimated_cost_log10", log10p(input.estimatedCost()));
        set(values, "seq_scan", flag(isSeqScan(input.scanType())));
        set(values, "estimate_missing", flag(input.estimateMissing()));

        setOneHot(values, QUERY_TYPE_INDEX, input.queryType());
        set(values, "transactional", flag(input.transactional()));
        set(values, "off_hours", flag(isOffHours(input)));

        set(values, "submitter_approval_rate", smoothedRate(submitterCounts));
        set(values, "datasource_approval_rate", smoothedRate(datasourceCounts));

        return new ApprovalFeatureVector(values);
    }

    private static void set(double[] values, String feature, double value) {
        Integer index = FEATURE_INDEX.get(feature);
        if (index == null) {
            throw new IllegalStateException("Unknown schema-v1 feature: " + feature);
        }
        values[index] = value;
    }

    private static <E extends Enum<E>> void setOneHot(
            double[] values, Map<E, Integer> indices, E constant) {
        if (constant == null) {
            return;
        }
        Integer index = indices.get(constant);
        if (index != null) {
            values[index] = 1.0;
        }
    }

    private static double flag(boolean value) {
        return value ? 1.0 : 0.0;
    }

    /**
     * {@code log10(1 + v)}, compressing the heavy right tail of row counts and plan costs. Absent,
     * non-finite and negative inputs all collapse to {@code 0.0} — their absence is carried by the
     * {@code estimate_missing} indicator instead.
     */
    private static double log10p(Number value) {
        if (value == null) {
            return 0.0;
        }
        double raw = value.doubleValue();
        if (!Double.isFinite(raw) || raw <= 0.0) {
            return 0.0;
        }
        return Math.log10(1.0 + raw);
    }

    /**
     * Laplace-smoothed approval rate, {@code (approved + 1) / (decided + 2)}. With no decided
     * history this is exactly {@code 0.5} — an uninformative prior rather than a hard 0 or 1 drawn
     * from one or two decisions.
     */
    private static double smoothedRate(ApprovalRateCounts counts) {
        if (counts == null) {
            return 0.5;
        }
        return (counts.approved() + 1.0) / (counts.decided() + 2.0);
    }

    /**
     * The one feature with no missing indicator: a null {@code createdAt} scores as business hours
     * rather than as "unknown". {@code query_requests.created_at} is {@code NOT NULL}, so this is
     * unreachable in practice and does not warrant a 22nd feature in a frozen schema.
     */
    private static boolean isOffHours(ApprovalFeatureInput input) {
        if (input.createdAt() == null) {
            return false;
        }
        int hour = input.createdAt().atOffset(FEATURE_ZONE).getHour();
        return hour < BUSINESS_HOURS_START || hour >= BUSINESS_HOURS_END;
    }

    private static boolean isSeqScan(String scanType) {
        if (scanType == null || scanType.isBlank()) {
            return false;
        }
        String normalized = WHITESPACE_RUN
                .matcher(scanType.trim().toLowerCase(Locale.ROOT))
                .replaceAll(" ");
        return SEQ_SCAN_OPERATIONS.contains(TRAILING_DIGITS.matcher(normalized).replaceAll(""));
    }
}
