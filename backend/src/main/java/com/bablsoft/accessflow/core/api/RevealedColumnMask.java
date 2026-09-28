package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/** A masking policy that does not apply to the user, with every reason it reveals to them (#946). */
public record RevealedColumnMask(UUID policyId, String columnRef, MaskingStrategy strategy,
                                 List<AccessTargetMatch> revealedBy) {

    public RevealedColumnMask {
        revealedBy = revealedBy == null ? List.of() : List.copyOf(revealedBy);
    }
}
