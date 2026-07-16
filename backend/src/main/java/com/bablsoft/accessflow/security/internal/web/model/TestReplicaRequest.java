package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Live-values request for {@code POST /api/v1/datasources/{id}/test-replica}. The {@code password}
 * field is optional — when omitted, the test reuses the persisted password of the endpoint named
 * by {@code replicaId} (lets an admin re-test after changing only the URL or username without
 * re-typing the secret).
 */
public record TestReplicaRequest(
        @NotBlank(message = "{validation.jdbc_url.required}")
        @Size(max = 2048, message = "{validation.jdbc_url.length}")
        @Pattern(regexp = "^jdbc:[a-zA-Z][a-zA-Z0-9+\\-.]*:.+$",
                message = "{validation.jdbc_url.format}") String jdbcUrl,
        @NotBlank(message = "{validation.username.required}")
        @Size(max = 255, message = "{validation.display_name.max}") String username,
        @Size(max = 4096) String password,
        UUID replicaId
) {}
