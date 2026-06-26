package com.bablsoft.accessflow.attestation.internal.web;

import jakarta.validation.constraints.Size;

/** Request body for certifying an item — an optional reviewer note. */
public record AttestationCertifyRequest(
        @Size(max = 4000, message = "{validation.review_comment.max}") String comment) {
}
