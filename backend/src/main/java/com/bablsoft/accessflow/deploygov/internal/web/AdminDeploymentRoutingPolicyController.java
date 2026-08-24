package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingPolicyService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Admin management of the ordered, attribute-based rules that route a deployment after AI analysis
 * (#691). Policies are returned in ascending {@code priority} — the order the engine evaluates them
 * in.
 */
@RestController
@RequestMapping("/api/v1/admin/deployment-routing-policies")
@Tag(name = "Deployment Routing Policies",
        description = "Policy-as-code routing for governed deployments (#691, epic #682)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE')")
class AdminDeploymentRoutingPolicyController {

    private final DeploymentRoutingPolicyService routingPolicyService;

    @GetMapping
    @Operation(summary = "List the organization's deployment routing policies (priority order)")
    @ApiResponse(responseCode = "200", description = "Routing policies, ascending priority")
    List<DeploymentRoutingPolicyResponse> list(Authentication authentication) {
        var caller = claims(authentication);
        return routingPolicyService.list(caller.organizationId()).stream()
                .map(DeploymentRoutingPolicyResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a deployment routing policy")
    @ApiResponse(responseCode = "200", description = "Routing policy")
    @ApiResponse(responseCode = "404", description = "Routing policy not found")
    DeploymentRoutingPolicyResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentRoutingPolicyResponse.from(
                routingPolicyService.get(id, caller.organizationId()));
    }

    @PostMapping
    @Operation(summary = "Create a deployment routing policy")
    @ApiResponse(responseCode = "201", description = "Routing policy created")
    @ApiResponse(responseCode = "400", description = "Invalid conditions or approval count")
    @ApiResponse(responseCode = "404", description = "Pipeline not found in this organization")
    @ApiResponse(responseCode = "409", description = "Another policy already uses that priority")
    ResponseEntity<DeploymentRoutingPolicyResponse> create(
            @Valid @RequestBody CreateDeploymentRoutingPolicyRequest body,
            Authentication authentication) {
        var caller = claims(authentication);
        var created = routingPolicyService.create(body.toCommand(caller.organizationId()));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(DeploymentRoutingPolicyResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a deployment routing policy (null fields stay unchanged)")
    @ApiResponse(responseCode = "200", description = "Routing policy updated")
    @ApiResponse(responseCode = "400", description = "Invalid conditions or approval count")
    @ApiResponse(responseCode = "404", description = "Routing policy or pipeline not found")
    @ApiResponse(responseCode = "409", description = "Another policy already uses that priority")
    DeploymentRoutingPolicyResponse update(@PathVariable UUID id,
                                           @Valid @RequestBody UpdateDeploymentRoutingPolicyRequest body,
                                           Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentRoutingPolicyResponse.from(
                routingPolicyService.update(id, caller.organizationId(), body.toCommand()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a deployment routing policy")
    @ApiResponse(responseCode = "204", description = "Routing policy deleted")
    @ApiResponse(responseCode = "404", description = "Routing policy not found")
    void delete(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        routingPolicyService.delete(id, caller.organizationId());
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
