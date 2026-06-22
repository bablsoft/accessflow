package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.security.api.StepUpService.StepUpToken;

import java.time.Instant;

/** The single-use step-up token and its expiry, returned after a successful verification. */
public record StepUpResponse(String stepUpToken, Instant expiresAt) {

    public static StepUpResponse from(StepUpToken token) {
        return new StepUpResponse(token.token(), token.expiresAt());
    }
}
