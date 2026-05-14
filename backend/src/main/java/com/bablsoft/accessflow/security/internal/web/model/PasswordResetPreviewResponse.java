package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.security.api.PasswordResetPreview;

import java.time.Instant;

public record PasswordResetPreviewResponse(String email, Instant expiresAt) {

    public static PasswordResetPreviewResponse from(PasswordResetPreview preview) {
        return new PasswordResetPreviewResponse(preview.email(), preview.expiresAt());
    }
}
