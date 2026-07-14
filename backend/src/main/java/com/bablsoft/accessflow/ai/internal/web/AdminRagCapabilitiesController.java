package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes deployment-level RAG capabilities to the admin UI (AF-336) so it can warn when the in-app
 * pgvector store is unavailable (e.g. the {@code vector} extension is not installed) and steer
 * admins toward an external Qdrant store.
 */
@RestController
@RequestMapping("/api/v1/admin/ai-configs/rag/capabilities")
@PreAuthorize("hasAuthority('PERM_AI_MANAGE')")
@Tag(name = "Admin RAG Capabilities",
        description = "Deployment-level RAG capabilities for the admin UI (ADMIN only)")
@RequiredArgsConstructor
class AdminRagCapabilitiesController {

    private final PgVectorAvailability pgVectorAvailability;

    @GetMapping
    @Operation(summary = "Whether in-app pgvector RAG storage is available on this deployment")
    @ApiResponse(responseCode = "200", description = "RAG capabilities")
    RagCapabilitiesResponse capabilities() {
        return new RagCapabilitiesResponse(pgVectorAvailability.isAvailable());
    }
}
