package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.ReviewDelegateCandidate;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** A colleague the caller may name as their delegate (#622). */
public record DelegateCandidateResponse(@JsonProperty("id") UUID id,
                                        @JsonProperty("email") String email,
                                        @JsonProperty("display_name") String displayName) {

    public static DelegateCandidateResponse from(ReviewDelegateCandidate candidate) {
        return new DelegateCandidateResponse(candidate.id(), candidate.email(),
                candidate.displayName());
    }
}
