package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * Every enabled masking policy on a datasource split by its effect on one user (#946):
 * {@code applied} is exactly {@link MaskingPolicyResolutionService#resolveApplicable}.
 */
public record MaskingExplanation(List<ResolvedColumnMask> applied, List<RevealedColumnMask> revealed) {

    public MaskingExplanation {
        applied = applied == null ? List.of() : List.copyOf(applied);
        revealed = revealed == null ? List.of() : List.copyOf(revealed);
    }
}
