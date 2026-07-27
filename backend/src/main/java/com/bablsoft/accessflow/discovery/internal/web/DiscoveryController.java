package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.discovery.api.DiscoveryConfigService;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingService;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingService.BulkDecisionOutcome;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryRowStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanTriggerService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/datasources/{datasourceId}/discovery")
@Tag(name = "Sensitive-Data Discovery",
        description = "Automated sensitive-data discovery scans and the proposed-classification worklist (AF-623)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_DATA_CLASSIFICATION_MANAGE')")
class DiscoveryController {

    private final DiscoveryConfigService configService;
    private final DiscoveryFindingService findingService;
    private final DiscoveryScanTriggerService scanTriggerService;
    private final DiscoveryAuditWriter auditWriter;

    @GetMapping("/config")
    @Operation(summary = "Get the datasource's discovery settings (defaults when never configured)")
    @ApiResponse(responseCode = "200", description = "Current or default settings")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    DiscoveryConfigResponse getConfig(@PathVariable UUID datasourceId,
                                      Authentication authentication) {
        var caller = currentClaims(authentication);
        return DiscoveryConfigResponse.from(configService.get(datasourceId,
                caller.organizationId()));
    }

    @PutMapping("/config")
    @Operation(summary = "Create or update the datasource's discovery settings")
    @ApiResponse(responseCode = "200", description = "Updated settings")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    DiscoveryConfigResponse updateConfig(@PathVariable UUID datasourceId,
                                         @Valid @RequestBody UpdateDiscoveryConfigRequest body,
                                         Authentication authentication) {
        var caller = currentClaims(authentication);
        return DiscoveryConfigResponse.from(configService.upsert(datasourceId,
                caller.organizationId(), body.toCommand()));
    }

    @GetMapping("/findings")
    @Operation(summary = "Page the discovery-findings worklist, newest detection first")
    @ApiResponse(responseCode = "200", description = "Page of findings")
    DiscoveryFindingPageResponse listFindings(@PathVariable UUID datasourceId,
                                              @RequestParam(required = false)
                                              DiscoveryFindingStatus status,
                                              Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = findingService.find(datasourceId, caller.organizationId(), status,
                SpringPageableAdapter.toPageRequest(pageable));
        return DiscoveryFindingPageResponse.from(page);
    }

    @PostMapping("/findings/bulk-decision")
    @Operation(summary = "Confirm or dismiss a batch of findings; rows are decided independently")
    @ApiResponse(responseCode = "200", description = "Per-row outcomes (partial success)")
    @ApiResponse(responseCode = "400", description = "Validation error (empty/oversized id list)")
    BulkDiscoveryDecisionResponse bulkDecision(@PathVariable UUID datasourceId,
                                               @Valid @RequestBody
                                               BulkDiscoveryDecisionRequest body,
                                               Authentication authentication,
                                               RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = findingService.decide(datasourceId, caller.organizationId(),
                caller.userId(), body.findingIds(), body.decision());
        recordDecisionAudits(datasourceId, outcome, caller, auditContext);
        return BulkDiscoveryDecisionResponse.from(outcome);
    }

    @PostMapping("/scan")
    @Operation(summary = "Trigger an immediate discovery scan (runs asynchronously)")
    @ApiResponse(responseCode = "202", description = "Scan accepted")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "409", description = "A scan for this datasource is already running")
    ResponseEntity<Void> triggerScan(@PathVariable UUID datasourceId,
                                     Authentication authentication) {
        var caller = currentClaims(authentication);
        scanTriggerService.requestScan(datasourceId, caller.organizationId(), caller.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void recordDecisionAudits(UUID datasourceId, BulkDecisionOutcome outcome,
                                      JwtClaims caller, RequestAuditContext auditContext) {
        for (var row : outcome.results()) {
            var decided = row.status() == DiscoveryRowStatus.SUCCESS
                    || row.status() == DiscoveryRowStatus.TAG_CONFLICT;
            if (!decided || row.finding() == null) {
                continue;
            }
            var action = row.newStatus() == DiscoveryFindingStatus.CONFIRMED
                    ? AuditAction.DISCOVERY_FINDING_CONFIRMED
                    : AuditAction.DISCOVERY_FINDING_DISMISSED;
            var finding = row.finding();
            var metadata = new HashMap<String, Object>();
            metadata.put("datasourceId", datasourceId.toString());
            metadata.put("tableName", finding.schemaName() == null ? finding.tableName()
                    : finding.schemaName() + "." + finding.tableName());
            metadata.put("columnName", finding.columnName());
            metadata.put("classification", finding.classification().name());
            metadata.put("detector", finding.detector().name());
            metadata.put("confidence", finding.confidence());
            metadata.put("tagConflict", row.status() == DiscoveryRowStatus.TAG_CONFLICT);
            auditWriter.record(action, row.findingId(), caller, metadata, auditContext);
        }
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
