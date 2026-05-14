package com.bablsoft.accessflow.security.api;

import java.time.Instant;

public record PasswordResetPreview(String email, Instant expiresAt) {
}
