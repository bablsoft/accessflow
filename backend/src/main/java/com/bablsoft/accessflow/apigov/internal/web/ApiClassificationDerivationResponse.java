package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationDerivationView;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.List;
import java.util.Map;

public record ApiClassificationDerivationResponse(
        ReviewPosture suggestedReviewPosture,
        List<MaskingSuggestion> maskingSuggestions) {

    public record ReviewPosture(
            boolean requiresAiReview,
            boolean requiresHumanApproval,
            int minApprovals,
            List<DataClassification> drivenBy) {
    }

    public record MaskingSuggestion(
            ApiMaskingMatcherType matcherType,
            String operationId,
            String fieldRef,
            DataClassification classification,
            MaskingStrategy suggestedStrategy,
            Map<String, String> suggestedParams,
            boolean alreadyApplied) {
    }

    public static ApiClassificationDerivationResponse from(ApiConnectorClassificationDerivationView view) {
        var posture = view.suggestedReviewPosture();
        var suggestions = view.maskingSuggestions().stream()
                .map(s -> new MaskingSuggestion(s.matcherType(), s.operationId(), s.fieldRef(),
                        s.classification(), s.suggestedStrategy(), s.suggestedParams(), s.alreadyApplied()))
                .toList();
        return new ApiClassificationDerivationResponse(
                new ReviewPosture(posture.requiresAiReview(), posture.requiresHumanApproval(),
                        posture.minApprovals(), posture.drivenBy()),
                suggestions);
    }
}
