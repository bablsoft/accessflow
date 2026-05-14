package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * Self-service password reset flow. Reset links are single-use, short-lived tokens delivered
 * via the organization's system SMTP. {@link #requestReset(String)} is enumeration-safe: it
 * never throws when the email is unknown, SSO-only, inactive, or when SMTP is missing.
 */
public interface PasswordResetService {

    /**
     * Issue a reset token for the given email when one is appropriate. Silently no-ops for
     * unknown / SSO-only / inactive / null-password-hash users, and when system SMTP is not
     * configured for the user's organization.
     */
    void requestReset(String email);

    /**
     * Validate the token and return the account it resets. Throws if the token is missing,
     * expired, used, or revoked.
     */
    PasswordResetPreview previewByToken(String plaintextToken);

    /**
     * Set the user's new password, revoke all active sessions, and mark the token used.
     * Returns the userId whose password was reset (for audit).
     */
    UUID resetPassword(String plaintextToken, String newPassword);
}
