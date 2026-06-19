package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationDerivationView;
import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.List;
import java.util.Map;

public record DataClassificationDerivationResponse(
        ReviewPostureResponse suggestedReviewPosture,
        List<MaskingSuggestionResponse> maskingSuggestions) {

    public record ReviewPostureResponse(
            boolean requiresAiReview,
            boolean requiresHumanApproval,
            int minApprovals,
            List<DataClassification> drivenBy) {
    }

    public record MaskingSuggestionResponse(
            String columnRef,
            DataClassification classification,
            MaskingStrategy suggestedStrategy,
            Map<String, String> suggestedParams,
            boolean alreadyApplied) {
    }

    public static DataClassificationDerivationResponse from(DataClassificationDerivationView view) {
        var posture = view.suggestedReviewPosture();
        var suggestions = view.maskingSuggestions().stream()
                .map(s -> new MaskingSuggestionResponse(s.columnRef(), s.classification(),
                        s.suggestedStrategy(), s.suggestedParams(), s.alreadyApplied()))
                .toList();
        return new DataClassificationDerivationResponse(
                new ReviewPostureResponse(posture.requiresAiReview(),
                        posture.requiresHumanApproval(), posture.minApprovals(), posture.drivenBy()),
                suggestions);
    }
}
