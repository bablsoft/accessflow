package com.bablsoft.accessflow.core.api;

import java.util.Map;
import java.util.UUID;

/**
 * A masking policy that <em>applies</em> to a given requester (i.e. the requester is not revealed),
 * resolved for one query execution. Carries the strategy and its parsed parameters so the proxy can
 * render the masked value, plus the originating {@code policyId} for audit.
 */
public record ResolvedColumnMask(
        UUID policyId,
        String columnRef,
        MaskingStrategy strategy,
        Map<String, String> params) {

    public ResolvedColumnMask {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
