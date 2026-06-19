package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Map;

/**
 * Read-only preview of the stricter handling implied by a datasource's classification tags.
 * Aggregates a suggested review posture (the union/strictest over all present classifications) and
 * the per-column masking suggestions derived from column-level tags. Computing this never mutates a
 * review plan — review plans are shared across datasources, so deriving a stricter posture is only
 * ever suggested for an admin to apply.
 */
public record DataClassificationDerivationView(
        ReviewPosture suggestedReviewPosture,
        List<MaskingSuggestion> maskingSuggestions) {

    public DataClassificationDerivationView {
        maskingSuggestions = maskingSuggestions == null ? List.of() : List.copyOf(maskingSuggestions);
    }

    /**
     * @param drivenBy the classifications present on the datasource that produced this posture
     */
    public record ReviewPosture(
            boolean requiresAiReview,
            boolean requiresHumanApproval,
            int minApprovals,
            List<DataClassification> drivenBy) {

        public ReviewPosture {
            drivenBy = drivenBy == null ? List.of() : List.copyOf(drivenBy);
        }
    }

    /**
     * @param alreadyApplied true when an enabled masking policy already covers {@code columnRef}
     */
    public record MaskingSuggestion(
            String columnRef,
            DataClassification classification,
            MaskingStrategy suggestedStrategy,
            Map<String, String> suggestedParams,
            boolean alreadyApplied) {

        public MaskingSuggestion {
            suggestedParams = suggestedParams == null ? Map.of() : Map.copyOf(suggestedParams);
        }
    }
}
