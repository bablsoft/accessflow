package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.List;
import java.util.Map;

/**
 * Read-only preview of the stricter handling implied by a connector's classification tags (AF-518).
 * Aggregates a suggested review posture (the union/strictest over all present classifications) and
 * the per-field masking suggestions derived from field-level tags. Computing this never mutates a
 * review plan — it is only ever suggested for an admin to apply.
 */
public record ApiConnectorClassificationDerivationView(
        ReviewPosture suggestedReviewPosture,
        List<MaskingSuggestion> maskingSuggestions) {

    public ApiConnectorClassificationDerivationView {
        maskingSuggestions = maskingSuggestions == null ? List.of() : List.copyOf(maskingSuggestions);
    }

    /**
     * @param drivenBy the classifications present on the connector that produced this posture
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
     * @param alreadyApplied true when an enabled masking policy already covers the same
     *                       (matcherType, operationId, fieldRef)
     */
    public record MaskingSuggestion(
            ApiMaskingMatcherType matcherType,
            String operationId,
            String fieldRef,
            DataClassification classification,
            MaskingStrategy suggestedStrategy,
            Map<String, String> suggestedParams,
            boolean alreadyApplied) {

        public MaskingSuggestion {
            suggestedParams = suggestedParams == null ? Map.of() : Map.copyOf(suggestedParams);
        }
    }
}
