package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionInventoryService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only version inventory and drift over the #741 tracking projection (#742).
 *
 * <p>Deliberately carries <strong>no class-level {@code @PreAuthorize}</strong> (the gate-controller
 * precedent): the two per-pipeline endpoints are open to effective {@code can_trigger} grant
 * holders, which is not a functional permission — the service enforces visibility and answers
 * 404, never 403, so the endpoints are not an existence oracle. Only the org-wide matrix carries
 * a method-level functional gate.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Deployment Version Inventory",
        description = "Which version runs where, and the drift indicator (#742, epic #682)")
@RequiredArgsConstructor
class DeploymentVersionInventoryController {

    private final DeploymentVersionInventoryService inventoryService;

    @GetMapping("/deployment-pipelines/{id}/environment-versions")
    @Operation(summary = "The version matrix for one pipeline, every environment by sort order")
    @ApiResponse(responseCode = "200", description = "Environment-version rows with drift")
    @ApiResponse(responseCode = "404",
            description = "Pipeline not found, or not visible to the caller")
    List<DeploymentEnvironmentVersionResponse> pipelineMatrix(@PathVariable UUID id,
                                                              Authentication authentication) {
        var caller = claims(authentication);
        return inventoryService.pipelineMatrix(id, caller.organizationId(), caller.userId(),
                        caller.permissions()).stream()
                .map(DeploymentEnvironmentVersionResponse::from)
                .toList();
    }

    @GetMapping("/deployment-environment-versions")
    @PreAuthorize("hasAnyAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE',"
            + "'PERM_DEPLOYMENT_REVIEW','PERM_QUERY_ADMIN')")
    @Operation(summary = "The org-wide environment-version matrix with drift")
    @ApiResponse(responseCode = "200", description = "Page of environment-version rows")
    @ApiResponse(responseCode = "403",
            description = "Caller holds none of the required permissions")
    DeploymentEnvironmentVersionPageResponse list(
            @RequestParam(name = "pipeline_id", required = false) UUID pipelineId,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "environment", required = false) String environment,
            @RequestParam(name = "drifted", required = false) Boolean drifted,
            Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        var filter = new DeploymentEnvironmentVersionListFilter(caller.organizationId(),
                pipelineId, tag, environment, drifted);
        return DeploymentEnvironmentVersionPageResponse.from(
                inventoryService.list(filter, SpringPageableAdapter.toPageRequest(pageable)));
    }

    @GetMapping("/deployment-pipelines/{id}/environments/{envId}/history")
    @Operation(summary = "The environment's deployment timeline, newest first")
    @ApiResponse(responseCode = "200", description = "Page of deployment history entries")
    @ApiResponse(responseCode = "404", description = "Pipeline or environment not found, or the "
            + "pipeline is not visible to the caller")
    DeploymentVersionHistoryPageResponse history(
            @PathVariable UUID id, @PathVariable UUID envId,
            @RequestParam(name = "status", required = false) QueryStatus status,
            Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        return DeploymentVersionHistoryPageResponse.from(inventoryService.history(id, envId,
                status, caller.organizationId(), caller.userId(), caller.permissions(),
                SpringPageableAdapter.toPageRequest(pageable)));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
