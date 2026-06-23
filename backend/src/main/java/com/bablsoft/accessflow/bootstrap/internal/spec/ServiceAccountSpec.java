package com.bablsoft.accessflow.bootstrap.internal.spec;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;

/**
 * Declarative spec for a CI / IaC service account and its API key (AF-452). The {@code apiKey} is a
 * caller-supplied raw token (sourced from a Secret) so a pipeline / Terraform run holds credentials
 * with no interactive login; only its hash is persisted. {@code role} defaults to {@code ADMIN}
 * (Terraform-style provisioning needs admin CRUD). {@code apiKeyExpiresAt} is optional (never
 * expires when null).
 */
public record ServiceAccountSpec(
        String email,
        String displayName,
        UserRoleType role,
        String apiKeyName,
        String apiKey,
        Instant apiKeyExpiresAt
) {
}
