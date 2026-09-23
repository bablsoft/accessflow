package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftConfigService;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/schema-drift")
@Tag(name = "Schema Drift",
        description = "Scheduled comparison of a deployment environment's live schema against a "
                + "baseline. Drift only ever records findings — it never writes to the database "
                + "it scans (epic #870)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')")
class SchemaDriftController {

    private final SchemaDriftService driftService;
    private final SchemaDriftConfigService configService;

    @GetMapping("/scans")
    @Operation(summary = "List drift scans, newest first",
            description = "An inapplicable scan (applicable=false) means the engine samples rather "
                    + "than reading a catalog and was never contacted, which is not the same as a "
                    + "scan that found nothing. error_message carries a stable reason code.")
    @ApiResponse(responseCode = "200", description = "Scans")
    SchemaDriftScanPageResponse listScans(@RequestParam(name = "pipeline_id", required = false) UUID pipelineId,
                                          @RequestParam(name = "environment_id", required = false) UUID environmentId,
                                          Pageable pageable, Authentication authentication) {
        var caller = claims(authentication);
        return SchemaDriftScanPageResponse.from(driftService.listScans(caller.organizationId(),
                new SchemaDriftScanListFilter(pipelineId, environmentId),
                SpringPageableAdapter.toPageRequest(pageable)));
    }

    @GetMapping("/findings")
    @Operation(summary = "List drift findings, most recently seen first")
    @ApiResponse(responseCode = "200", description = "Findings")
    SchemaDriftFindingPageResponse listFindings(@RequestParam(name = "pipeline_id", required = false) UUID pipelineId,
                                                @RequestParam(name = "environment_id", required = false) UUID environmentId,
                                                @RequestParam(name = "status", required = false) SchemaDriftFindingStatus status,
                                                Pageable pageable, Authentication authentication) {
        var caller = claims(authentication);
        return SchemaDriftFindingPageResponse.from(driftService.listFindings(caller.organizationId(),
                new SchemaDriftFindingListFilter(pipelineId, environmentId, status),
                SpringPageableAdapter.toPageRequest(pageable)));
    }

    @PostMapping("/scans")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Scan one environment now",
            description = "Returns as soon as the scan is accepted, carrying the id to poll — "
                    + "introspection opens a connection to a customer database and cannot sit on a "
                    + "request thread.")
    @ApiResponse(responseCode = "202", description = "Scan accepted; poll the returned id")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Environment not found in this organization")
    @ApiResponse(responseCode = "409", description = "A scan of this environment is already running "
            + "somewhere in the cluster")
    @ApiResponse(responseCode = "422", description = "The environment binds no datasource")
    SchemaDriftScanResponse scanNow(@Valid @RequestBody RequestSchemaDriftScanRequest body,
                                    Authentication authentication) {
        var caller = claims(authentication);
        return SchemaDriftScanResponse.from(driftService.scanNow(caller.organizationId(), caller.userId(),
                body.environmentId()));
    }

    @PostMapping("/findings/{id}/acknowledge")
    @Operation(summary = "Accept a drift finding",
            description = "Accepts the difference as it stands. A later scan that sees the same "
                    + "object path with different values reopens it.")
    @ApiResponse(responseCode = "200", description = "Finding acknowledged")
    @ApiResponse(responseCode = "404", description = "Finding not found in this organization")
    @ApiResponse(responseCode = "409", description = "The finding has already been resolved")
    SchemaDriftFindingResponse acknowledge(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return SchemaDriftFindingResponse.from(driftService.acknowledge(caller.organizationId(),
                caller.userId(), id));
    }

    @GetMapping("/configs")
    @Operation(summary = "List the organization's configured pipelines",
            description = "Pipelines never configured are not listed; drift is off for them.")
    @ApiResponse(responseCode = "200", description = "Configurations")
    List<SchemaDriftConfigResponse> listConfigs(Authentication authentication) {
        var caller = claims(authentication);
        return configService.list(caller.organizationId()).stream()
                .map(SchemaDriftConfigResponse::from)
                .toList();
    }

    @GetMapping("/configs/{pipelineId}")
    @Operation(summary = "Get one pipeline's drift configuration",
            description = "Synthesizes the disabled defaults when the pipeline has never been "
                    + "configured — a null id means exactly what enabled=false means.")
    @ApiResponse(responseCode = "200", description = "Configuration")
    @ApiResponse(responseCode = "404", description = "Pipeline not found in this organization")
    SchemaDriftConfigResponse getConfig(@PathVariable UUID pipelineId, Authentication authentication) {
        var caller = claims(authentication);
        return SchemaDriftConfigResponse.from(configService.get(caller.organizationId(), pipelineId));
    }

    @PutMapping("/configs/{pipelineId}")
    @Operation(summary = "Create or replace one pipeline's drift configuration",
            description = "A replacement, not a patch: an omitted baseline environment clears the "
                    + "designation rather than keeping a stale one.")
    @ApiResponse(responseCode = "200", description = "Configuration saved")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Pipeline not found in this organization")
    @ApiResponse(responseCode = "422", description = "The designated baseline environment is not a "
            + "datasource-bound environment of this pipeline")
    SchemaDriftConfigResponse upsertConfig(@PathVariable UUID pipelineId,
                                           @Valid @RequestBody UpsertSchemaDriftConfigRequest body,
                                           Authentication authentication) {
        var caller = claims(authentication);
        return SchemaDriftConfigResponse.from(configService.upsert(caller.organizationId(), caller.userId(),
                pipelineId, body.toCommand()));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
