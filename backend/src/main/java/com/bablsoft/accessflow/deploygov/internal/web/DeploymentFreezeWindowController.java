package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployment-freeze-windows")
@Tag(name = "Deployment Freeze Windows",
        description = "One-off and recurring deployment freeze windows (epic #682)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE')")
class DeploymentFreezeWindowController {

    private final DeploymentFreezeWindowService freezeWindowService;

    @GetMapping
    @Operation(summary = "List the organization's freeze windows")
    @ApiResponse(responseCode = "200", description = "Page of freeze windows")
    DeploymentFreezeWindowPageResponse list(Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        var page = freezeWindowService.list(caller.organizationId(),
                SpringPageableAdapter.toPageRequest(pageable));
        return DeploymentFreezeWindowPageResponse.from(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a freeze window")
    @ApiResponse(responseCode = "200", description = "Freeze window")
    @ApiResponse(responseCode = "404", description = "Freeze window not found")
    DeploymentFreezeWindowResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentFreezeWindowResponse.from(
                freezeWindowService.get(id, caller.organizationId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a freeze window (one-off startsAt/endsAt, or recurring "
            + "daysOfWeek + startTime/endTime + timezone — exactly one shape)")
    @ApiResponse(responseCode = "201", description = "Freeze window created")
    @ApiResponse(responseCode = "400", description = "Shape or scope violation")
    @ApiResponse(responseCode = "404", description = "Scoped pipeline or environment not found")
    DeploymentFreezeWindowResponse create(@Valid @RequestBody DeploymentFreezeWindowRequest body,
                                          Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentFreezeWindowResponse.from(
                freezeWindowService.create(body.toCommand(caller.organizationId())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a freeze window's definition (full replacement — same body as create)")
    @ApiResponse(responseCode = "200", description = "Freeze window replaced")
    @ApiResponse(responseCode = "400", description = "Shape or scope violation")
    @ApiResponse(responseCode = "404", description = "Freeze window, pipeline, or environment not found")
    DeploymentFreezeWindowResponse update(@PathVariable UUID id,
                                          @Valid @RequestBody DeploymentFreezeWindowRequest body,
                                          Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentFreezeWindowResponse.from(
                freezeWindowService.update(id, body.toCommand(caller.organizationId())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a freeze window")
    @ApiResponse(responseCode = "204", description = "Freeze window deleted")
    @ApiResponse(responseCode = "404", description = "Freeze window not found")
    void delete(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        freezeWindowService.delete(id, caller.organizationId());
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
