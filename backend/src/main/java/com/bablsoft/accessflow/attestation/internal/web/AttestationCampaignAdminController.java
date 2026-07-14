package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignAdminService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationEvidenceExportService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/attestation-campaigns")
@Tag(name = "Attestation Campaigns (Admin)",
        description = "Create and manage access-recertification campaigns")
@RequiredArgsConstructor
class AttestationCampaignAdminController {

    private final AttestationCampaignAdminService adminService;
    private final AttestationEvidenceExportService evidenceExportService;
    private final AttestationAuditWriter auditWriter;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_CAMPAIGN_MANAGE')")
    @Operation(summary = "List attestation campaigns, optionally filtered by status")
    @ApiResponse(responseCode = "200", description = "Page of campaigns")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    AttestationCampaignPageResponse list(@RequestParam(required = false) AttestationCampaignStatus status,
                                         Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = adminService.list(caller.organizationId(), status,
                SpringPageableAdapter.toPageRequest(pageable));
        return AttestationCampaignPageResponse.from(page);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_CAMPAIGN_MANAGE')")
    @Operation(summary = "Create a SCHEDULED attestation campaign")
    @ApiResponse(responseCode = "201", description = "Campaign created")
    @ApiResponse(responseCode = "400", description = "Validation error or inconsistent scope")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Datasource not found (DATASOURCE scope)")
    AttestationCampaignResponse create(@Valid @RequestBody CreateAttestationCampaignRequest body,
                                       Authentication authentication) {
        var caller = currentClaims(authentication);
        var view = adminService.create(body.toCommand(caller.organizationId(), caller.userId()));
        return AttestationCampaignResponse.from(view);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_CAMPAIGN_MANAGE')")
    @Operation(summary = "Get a campaign with its item-decision breakdown")
    @ApiResponse(responseCode = "200", description = "Campaign")
    @ApiResponse(responseCode = "404", description = "Campaign not found")
    AttestationCampaignResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return AttestationCampaignResponse.from(adminService.get(id, caller.organizationId()));
    }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_CAMPAIGN_MANAGE')")
    @Operation(summary = "List the items (snapshotted grants) of a campaign")
    @ApiResponse(responseCode = "200", description = "Page of items")
    @ApiResponse(responseCode = "404", description = "Campaign not found")
    AttestationItemPageResponse listItems(@PathVariable UUID id, Authentication authentication,
                                          Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = adminService.listItems(id, caller.organizationId(),
                SpringPageableAdapter.toPageRequest(pageable));
        return AttestationItemPageResponse.from(page);
    }

    @PostMapping("/{id}/open")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_CAMPAIGN_MANAGE')")
    @Operation(summary = "Open a SCHEDULED campaign immediately (snapshots grants); idempotent")
    @ApiResponse(responseCode = "200", description = "Campaign (now OPEN, or unchanged if already OPEN)")
    @ApiResponse(responseCode = "404", description = "Campaign not found")
    AttestationCampaignResponse open(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return AttestationCampaignResponse.from(adminService.openNow(id, caller.organizationId()));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_CAMPAIGN_MANAGE')")
    @Operation(summary = "Cancel a SCHEDULED campaign")
    @ApiResponse(responseCode = "204", description = "Campaign cancelled")
    @ApiResponse(responseCode = "404", description = "Campaign not found")
    @ApiResponse(responseCode = "409", description = "Campaign is not SCHEDULED")
    void cancel(@PathVariable UUID id, Authentication authentication,
                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        adminService.cancel(id, caller.organizationId());
        auditWriter.record(AuditAction.ATTESTATION_CAMPAIGN_CANCELLED,
                AuditResourceType.ATTESTATION_CAMPAIGN, id, caller, new HashMap<>(), auditContext);
    }

    @GetMapping(value = "/{id}/evidence.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_EVIDENCE_EXPORT')")
    @Operation(summary = "Export a campaign's attestation evidence as CSV")
    @ApiResponse(responseCode = "200", description = "CSV evidence")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin or auditor")
    @ApiResponse(responseCode = "404", description = "Campaign not found")
    ResponseEntity<byte[]> exportEvidence(@PathVariable UUID id, Authentication authentication,
                                          RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var export = evidenceExportService.export(id, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("row_count", export.rowCount());
        metadata.put("truncated", export.truncated());
        auditWriter.record(AuditAction.ATTESTATION_EVIDENCE_EXPORTED,
                AuditResourceType.ATTESTATION_CAMPAIGN, id, caller, metadata, auditContext);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + export.filename() + "\"");
        headers.add("X-AccessFlow-Export-Truncated", Boolean.toString(export.truncated()));
        return ResponseEntity.ok().headers(headers).body(export.content());
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
