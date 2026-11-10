package com.bablsoft.accessflow.deploygov.api;

/** Discriminator of a {@link VerificationCheck}, mirroring the {@code verification_check_kind} PG enum. */
public enum VerificationCheckKind {
    HTTP_HEALTH,
    PROMQL_THRESHOLD
}
