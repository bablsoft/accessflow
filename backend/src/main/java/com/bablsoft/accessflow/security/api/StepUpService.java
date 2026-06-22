package com.bablsoft.accessflow.security.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Secondary ("step-up") authentication for high-assurance actions that must not commit on a single
 * tap — notably the one-tap push approve/reject (AF-444). The caller re-verifies an existing
 * credential (their password, or a TOTP code when 2FA is enrolled); on success a short-lived,
 * single-use token is minted. The action endpoint then {@link #consume(String) consumes} that
 * token to prove the step-up happened.
 *
 * <p>Pure {@code api} type — primitives and project types only.
 */
public interface StepUpService {

    /**
     * Verifies the supplied credential for the user and, on success, issues a single-use step-up
     * token. Throws {@link StepUpVerificationException} when neither the password nor the TOTP code
     * verifies.
     */
    StepUpToken issue(UUID userId, String email, String password, String totpCode);

    /**
     * Consumes a step-up token, returning the user it was issued to. Throws
     * {@link StepUpRequiredException} when the token is missing, expired, or already used.
     */
    UUID consume(String token);

    record StepUpToken(String token, Instant expiresAt) {
    }
}
