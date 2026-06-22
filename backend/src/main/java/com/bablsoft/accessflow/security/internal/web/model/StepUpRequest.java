package com.bablsoft.accessflow.security.internal.web.model;

/**
 * Step-up verification request (AF-444). Supply exactly one credential: {@code password} for
 * local-password users, or {@code totpCode} when 2FA is enrolled. Both are optional at the
 * Bean-Validation layer because the "one of two" choice is resolved (and rejected) in the service.
 */
public record StepUpRequest(String password, String totpCode) {
}
