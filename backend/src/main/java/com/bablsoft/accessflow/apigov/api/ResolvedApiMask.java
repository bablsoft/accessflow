package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.MaskingStrategy;

import java.util.Map;
import java.util.UUID;

/**
 * A connector masking policy that <em>applies</em> to a given requester (i.e. the requester is not
 * revealed), resolved for one API-call execution (AF-518). Carries the matcher, the strategy and its
 * parsed parameters so {@code ApiResponseMasker} can render the masked value, plus the originating
 * {@code policyId} for audit. A {@code null} {@code policyId} marks a legacy
 * {@code restricted_response_fields} entry (a FULL JSON-path mask kept for back-compat).
 */
public record ResolvedApiMask(
        UUID policyId,
        ApiMaskingMatcherType matcherType,
        String operationId,
        String fieldRef,
        MaskingStrategy strategy,
        Map<String, String> params) {

    public ResolvedApiMask {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** A legacy restricted-field entry: a FULL mask over a JSON dot-path, no originating policy. */
    public static ResolvedApiMask legacyRestrictedField(String fieldRef) {
        return new ResolvedApiMask(null, ApiMaskingMatcherType.JSON_PATH, null, fieldRef,
                MaskingStrategy.FULL, Map.of());
    }
}
