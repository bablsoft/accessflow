package com.bablsoft.accessflow.attestation.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for revoking an item — a justification comment is required. */
public record AttestationRevokeRequest(
        @NotBlank(message = "{validation.review_comment.required}")
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
