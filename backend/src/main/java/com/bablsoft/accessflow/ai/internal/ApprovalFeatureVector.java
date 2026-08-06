package com.bablsoft.accessflow.ai.internal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One query's numeric feature vector for the approval-outcome predictor (issue AF-650), in the
 * frozen schema-v1 layout described by {@link #FEATURE_SCHEMA_V1}.
 *
 * <p><strong>The name order is the contract.</strong> {@link TrainedApprovalModel} keys its
 * weights, means and stddevs by feature name but reads {@code rawFeatures} positionally, and
 * {@code predict} validates only the array <em>length</em>. Reordering, inserting or renaming an
 * entry within schema v1 therefore does not fail loudly — it silently scores every already-trained
 * model against the wrong coefficients. Any such change is a schema-v2 event: bump
 * {@link #SCHEMA_VERSION}, add a new list, and retrain.
 *
 * <p>Every value is finite by construction: the canonical constructor rejects {@code NaN} and the
 * infinities, so the "a missing input never produces {@code NaN}" rule holds structurally rather
 * than only under test.
 */
record ApprovalFeatureVector(double[] values) {

    /** Feature schema version, matching the {@code INTEGER} columns added by {@code V130}/{@code V131}. */
    static final int SCHEMA_VERSION = 1;

    /**
     * The canonical, ordered schema-v1 feature names. Grouped as they are derived: AI analysis,
     * cost estimate, query request, then historical rates.
     */
    static final List<String> FEATURE_SCHEMA_V1 = List.of(
            "risk_score",
            "risk_level_LOW",
            "risk_level_MEDIUM",
            "risk_level_HIGH",
            "risk_level_CRITICAL",
            "issue_count",
            "ai_missing",
            "estimated_rows_log10",
            "affected_row_count_log10",
            "estimated_cost_log10",
            "seq_scan",
            "estimate_missing",
            "query_type_SELECT",
            "query_type_INSERT",
            "query_type_UPDATE",
            "query_type_DELETE",
            "query_type_DDL",
            "transactional",
            "off_hours",
            "submitter_approval_rate",
            "datasource_approval_rate");

    /** Index of {@code estimate_missing}, the one feature persisted as a JSON boolean. */
    private static final int ESTIMATE_MISSING_INDEX = requireFeature("estimate_missing");

    private static int requireFeature(String name) {
        int index = FEATURE_SCHEMA_V1.indexOf(name);
        if (index < 0) {
            throw new IllegalStateException("Unknown schema-v1 feature: " + name);
        }
        return index;
    }

    ApprovalFeatureVector {
        Objects.requireNonNull(values, "values");
        if (values.length != FEATURE_SCHEMA_V1.size()) {
            throw new IllegalArgumentException(
                    "Feature length mismatch: expected " + FEATURE_SCHEMA_V1.size()
                    + ", got " + values.length);
        }
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                throw new IllegalArgumentException(
                        "Non-finite value for feature '" + FEATURE_SCHEMA_V1.get(i)
                        + "': " + values[i]);
            }
        }
        values = values.clone();
    }

    @Override
    public double[] values() {
        return values.clone();
    }

    /**
     * The name-to-value snapshot persisted as {@code approval_predictions.features} for
     * explainability, in schema order.
     *
     * <p>{@code estimate_missing} is written as a JSON <em>boolean</em>, not as {@code 1.0}:
     * {@code DefaultApprovalPredictionPersistenceService} gates its only replace-in-place path on
     * {@code readTree(featuresJson).path("estimate_missing").asBoolean(false)}, and a Jackson
     * {@code DoubleNode} coerces to {@code false} there. Writing a number would silently and
     * permanently disable recomputing a prediction once a late cost estimate arrives.
     */
    Map<String, Object> asSnapshotMap() {
        var snapshot = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i++) {
            snapshot.put(FEATURE_SCHEMA_V1.get(i), values[i]);
        }
        // Overwrites in place — LinkedHashMap keeps the original insertion position on re-put.
        snapshot.put("estimate_missing", values[ESTIMATE_MISSING_INDEX] != 0.0);
        return snapshot;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApprovalFeatureVector other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        var text = new StringBuilder("ApprovalFeatureVector[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(FEATURE_SCHEMA_V1.get(i)).append('=').append(values[i]);
        }
        return text.append(']').toString();
    }
}
