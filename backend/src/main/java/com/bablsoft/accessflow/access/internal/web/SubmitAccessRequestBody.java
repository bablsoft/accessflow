package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/access-requests}. Targets exactly one of
 * {@code datasourceId} / {@code connectorId} (enforced by {@link ExactlyOneResource}, which also
 * rejects datasource-only fields on connector requests and vice versa). The ISO-8601
 * {@code requestedDuration} pattern allows days/hours/minutes/seconds (no months/years — those are
 * not {@code Duration}s); the configured min/max bounds are enforced server-side in the service.
 */
@AtLeastOneCapability
@ExactlyOneResource
public record SubmitAccessRequestBody(
        UUID datasourceId,

        UUID connectorId,

        Boolean canRead,
        Boolean canWrite,
        Boolean canDdl,

        Boolean preApproveQueries,

        @Size(max = 50, message = "{validation.access.schemas.max}")
        List<@NotBlank(message = "{validation.access.schema.blank}") String> allowedSchemas,

        @Size(max = 200, message = "{validation.access.tables.max}")
        List<@NotBlank(message = "{validation.access.table.blank}") String> allowedTables,

        @Size(max = 200, message = "{validation.access.operations.max}")
        List<@NotBlank(message = "{validation.access.operation.blank}") String> allowedOperations,

        @NotBlank(message = "{validation.access.duration.required}")
        @Pattern(regexp = "^P(?!$)(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+S)?)?$",
                message = "{validation.access.duration.format}")
        String requestedDuration,

        @NotBlank(message = "{validation.access.justification.required}")
        @Size(max = 4000, message = "{validation.justification.max}")
        String justification) {
}
