package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployment-pipelines")
@Tag(name = "Deployment Pipelines",
        description = "Register and govern CI/CD deployment pipelines (epic #682)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE')")
class DeploymentPipelineController {

    private final DeploymentPipelineAdminService pipelineService;
    private final DeploymentPermissionService permissionService;

    @GetMapping
    @Operation(summary = "List the organization's deployment pipelines")
    @ApiResponse(responseCode = "200", description = "Page of pipelines")
    DeploymentPipelinePageResponse list(Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        var page = pipelineService.list(caller.organizationId(),
                SpringPageableAdapter.toPageRequest(pageable));
        return DeploymentPipelinePageResponse.from(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a deployment pipeline")
    @ApiResponse(responseCode = "200", description = "Pipeline")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    DeploymentPipelineResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentPipelineResponse.from(pipelineService.get(id, caller.organizationId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a deployment pipeline")
    @ApiResponse(responseCode = "201", description = "Pipeline created")
    @ApiResponse(responseCode = "404", description = "Review plan not found in this organization")
    @ApiResponse(responseCode = "409", description = "A pipeline with this name already exists")
    DeploymentPipelineResponse create(@Valid @RequestBody CreateDeploymentPipelineRequest body,
                                      Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentPipelineResponse.from(
                pipelineService.create(body.toCommand(caller.organizationId())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a deployment pipeline (null fields stay unchanged; "
            + "clearReviewPlan / clearAiConfig unassign the nullable references)")
    @ApiResponse(responseCode = "200", description = "Pipeline updated")
    @ApiResponse(responseCode = "404", description = "Pipeline or review plan not found")
    @ApiResponse(responseCode = "409", description = "A pipeline with this name already exists")
    DeploymentPipelineResponse update(@PathVariable UUID id,
                                      @Valid @RequestBody UpdateDeploymentPipelineRequest body,
                                      Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentPipelineResponse.from(
                pipelineService.update(id, caller.organizationId(), body.toCommand()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a deployment pipeline, cascading its environments, "
            + "freeze windows, and permission grants")
    @ApiResponse(responseCode = "204", description = "Pipeline deleted")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    void delete(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        pipelineService.delete(id, caller.organizationId());
    }

    @GetMapping("/{id}/environments")
    @Operation(summary = "List a pipeline's environments, ordered by sort order then name")
    @ApiResponse(responseCode = "200", description = "Environment list")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    List<DeploymentEnvironmentResponse> listEnvironments(@PathVariable UUID id,
                                                         Authentication authentication) {
        var caller = claims(authentication);
        return pipelineService.listEnvironments(id, caller.organizationId()).stream()
                .map(DeploymentEnvironmentResponse::from)
                .toList();
    }

    @PostMapping("/{id}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an environment on a pipeline")
    @ApiResponse(responseCode = "201", description = "Environment created")
    @ApiResponse(responseCode = "404", description = "Pipeline or review plan not found")
    @ApiResponse(responseCode = "409", description = "An environment with this name already exists")
    DeploymentEnvironmentResponse createEnvironment(
            @PathVariable UUID id, @Valid @RequestBody CreateDeploymentEnvironmentRequest body,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentEnvironmentResponse.from(
                pipelineService.createEnvironment(id, caller.organizationId(), body.toCommand()));
    }

    @PutMapping("/{id}/environments/{envId}")
    @Operation(summary = "Update an environment (null fields stay unchanged)")
    @ApiResponse(responseCode = "200", description = "Environment updated")
    @ApiResponse(responseCode = "404", description = "Pipeline, environment, or review plan not found")
    @ApiResponse(responseCode = "409", description = "An environment with this name already exists")
    DeploymentEnvironmentResponse updateEnvironment(
            @PathVariable UUID id, @PathVariable UUID envId,
            @Valid @RequestBody UpdateDeploymentEnvironmentRequest body,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentEnvironmentResponse.from(pipelineService.updateEnvironment(
                id, caller.organizationId(), envId, body.toCommand()));
    }

    @DeleteMapping("/{id}/environments/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an environment")
    @ApiResponse(responseCode = "204", description = "Environment deleted")
    @ApiResponse(responseCode = "404", description = "Pipeline or environment not found")
    void deleteEnvironment(@PathVariable UUID id, @PathVariable UUID envId,
                           Authentication authentication) {
        var caller = claims(authentication);
        pipelineService.deleteEnvironment(id, caller.organizationId(), envId);
    }

    @GetMapping("/{id}/permissions")
    @Operation(summary = "List per-user trigger grants on a pipeline")
    @ApiResponse(responseCode = "200", description = "Permission list")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    List<DeploymentPermissionResponse> listPermissions(@PathVariable UUID id,
                                                       Authentication authentication) {
        var caller = claims(authentication);
        return permissionService.listPermissions(id, caller.organizationId()).stream()
                .map(DeploymentPermissionResponse::from)
                .toList();
    }

    @PostMapping("/{id}/permissions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Grant or update a user's trigger permission on a pipeline")
    @ApiResponse(responseCode = "201", description = "Permission granted")
    @ApiResponse(responseCode = "404", description = "Pipeline or user not found")
    DeploymentPermissionResponse grant(@PathVariable UUID id,
                                       @Valid @RequestBody GrantDeploymentPermissionRequest body,
                                       Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentPermissionResponse.from(permissionService.grantPermission(
                id, caller.organizationId(), caller.userId(), body.toCommand()));
    }

    @PutMapping("/{id}/permissions/{permissionId}")
    @Operation(summary = "Update a user's trigger permission on a pipeline")
    @ApiResponse(responseCode = "200", description = "Permission updated")
    @ApiResponse(responseCode = "404", description = "Pipeline or permission not found")
    DeploymentPermissionResponse updatePermission(
            @PathVariable UUID id, @PathVariable UUID permissionId,
            @Valid @RequestBody UpdateDeploymentPermissionRequest body,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentPermissionResponse.from(permissionService.updatePermission(
                id, caller.organizationId(), permissionId, body.toCommand()));
    }

    @DeleteMapping("/{id}/permissions/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a user's trigger permission on a pipeline")
    @ApiResponse(responseCode = "204", description = "Permission revoked")
    @ApiResponse(responseCode = "404", description = "Pipeline or permission not found")
    void revoke(@PathVariable UUID id, @PathVariable UUID permissionId,
                Authentication authentication) {
        var caller = claims(authentication);
        permissionService.revokePermission(id, caller.organizationId(), permissionId);
    }

    @GetMapping("/{id}/permissions/groups")
    @Operation(summary = "List per-group trigger grants on a pipeline")
    @ApiResponse(responseCode = "200", description = "Group permission list")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    List<DeploymentGroupPermissionResponse> listGroupPermissions(@PathVariable UUID id,
                                                                 Authentication authentication) {
        var caller = claims(authentication);
        return permissionService.listGroupPermissions(id, caller.organizationId()).stream()
                .map(DeploymentGroupPermissionResponse::from)
                .toList();
    }

    @PostMapping("/{id}/permissions/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Grant or update a user group's trigger permission on a pipeline")
    @ApiResponse(responseCode = "201", description = "Group permission granted")
    @ApiResponse(responseCode = "404", description = "Pipeline or group not found")
    DeploymentGroupPermissionResponse grantGroup(
            @PathVariable UUID id, @Valid @RequestBody GrantDeploymentGroupPermissionRequest body,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentGroupPermissionResponse.from(permissionService.grantGroupPermission(
                id, caller.organizationId(), caller.userId(), body.toCommand()));
    }

    @PutMapping("/{id}/permissions/groups/{permissionId}")
    @Operation(summary = "Update a user group's trigger permission on a pipeline")
    @ApiResponse(responseCode = "200", description = "Group permission updated")
    @ApiResponse(responseCode = "404", description = "Pipeline or permission not found")
    DeploymentGroupPermissionResponse updateGroup(
            @PathVariable UUID id, @PathVariable UUID permissionId,
            @Valid @RequestBody UpdateDeploymentGroupPermissionRequest body,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentGroupPermissionResponse.from(permissionService.updateGroupPermission(
                id, caller.organizationId(), permissionId, body.toCommand()));
    }

    @DeleteMapping("/{id}/permissions/groups/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a user group's trigger permission on a pipeline")
    @ApiResponse(responseCode = "204", description = "Group permission revoked")
    @ApiResponse(responseCode = "404", description = "Pipeline or permission not found")
    void revokeGroup(@PathVariable UUID id, @PathVariable UUID permissionId,
                     Authentication authentication) {
        var caller = claims(authentication);
        permissionService.revokeGroupPermission(id, caller.organizationId(), permissionId);
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
