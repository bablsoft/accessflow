package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One read-replica endpoint in a datasource create/update payload (AF-457). {@code id} references
 * an existing endpoint on update ({@code null} creates a new one). {@code password} semantics on
 * update: {@code null} keeps the stored secret, empty string clears it (primary-credential
 * fallback), non-blank re-encrypts.
 */
public record ReadReplicaRequest(
        UUID id,
        @NotBlank(message = "{validation.jdbc_url.required}")
        @Size(max = 2048, message = "{validation.jdbc_url.length}")
        @Pattern(regexp = "^jdbc:[a-zA-Z][a-zA-Z0-9+\\-.]*:.+$",
                message = "{validation.jdbc_url.format}") String jdbcUrl,
        @Size(max = 255, message = "{validation.display_name.max}") String username,
        @Size(max = 4096) String password
) {}
