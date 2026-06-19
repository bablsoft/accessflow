package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateDataClassificationTagCommand;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateDataClassificationTagRequest;
import com.bablsoft.accessflow.security.internal.web.model.DataClassificationDerivationResponse;
import com.bablsoft.accessflow.security.internal.web.model.DataClassificationTagListResponse;
import com.bablsoft.accessflow.security.internal.web.model.DataClassificationTagResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/datasources/{datasourceId}/classification-tags")
@Tag(name = "Data classification tags",
        description = "Tag datasource tables/columns with data classifications (PII/PCI/PHI/GDPR/"
                + "FINANCIAL/SENSITIVE). Tags are immutable (create/delete only); a column-level tag "
                + "auto-applies a derived masking policy.")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
class DataClassificationTagController {

    private final DataClassificationAdminService dataClassificationAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List data-classification tags configured on a datasource")
    @ApiResponse(responseCode = "200", description = "List of classification tags")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    DataClassificationTagListResponse list(@PathVariable UUID datasourceId,
                                           Authentication authentication) {
        var caller = currentClaims(authentication);
        var tags = dataClassificationAdminService
                .listForDatasource(datasourceId, caller.organizationId()).stream()
                .map(DataClassificationTagResponse::from)
                .toList();
        return new DataClassificationTagListResponse(tags);
    }

    @PostMapping
    @Operation(summary = "Tag a datasource object with one or more data classifications")
    @ApiResponse(responseCode = "201", description = "Classification tags created (one per class)")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "Invalid input or duplicate tag")
    ResponseEntity<DataClassificationTagListResponse> create(
            @PathVariable UUID datasourceId,
            @Valid @RequestBody CreateDataClassificationTagRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var applyMasking = request.applyMasking() == null || request.applyMasking();
        var command = new CreateDataClassificationTagCommand(
                request.tableName(),
                request.columnName(),
                request.classifications(),
                request.note(),
                request.applyMasking());
        var views = dataClassificationAdminService.create(datasourceId, caller.organizationId(), command);
        for (var view : views) {
            recordAdded(view, applyMasking, caller, auditContext);
        }
        var responses = views.stream().map(DataClassificationTagResponse::from).toList();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(location)
                .body(new DataClassificationTagListResponse(responses));
    }

    @DeleteMapping("/{tagId}")
    @Operation(summary = "Delete a data-classification tag (does not remove the derived masking policy)")
    @ApiResponse(responseCode = "204", description = "Tag deleted")
    @ApiResponse(responseCode = "404", description = "Datasource or tag not found")
    ResponseEntity<Void> delete(@PathVariable UUID datasourceId,
                                @PathVariable UUID tagId,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        dataClassificationAdminService.delete(tagId, datasourceId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", datasourceId.toString());
        recordAudit(AuditAction.DATA_CLASSIFICATION_TAG_REMOVED, tagId, caller, auditContext, metadata);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/derivation-preview")
    @Operation(summary = "Preview the stricter masking + review handling implied by a datasource's tags")
    @ApiResponse(responseCode = "200", description = "Derivation preview")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    DataClassificationDerivationResponse derivationPreview(@PathVariable UUID datasourceId,
                                                           Authentication authentication) {
        var caller = currentClaims(authentication);
        var view = dataClassificationAdminService.previewDerivation(datasourceId, caller.organizationId());
        return DataClassificationDerivationResponse.from(view);
    }

    private void recordAdded(DataClassificationTagView view, boolean applyMasking, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", view.datasourceId().toString());
        metadata.put("table_name", view.tableName());
        metadata.put("column_name", view.columnName() == null ? "*" : view.columnName());
        metadata.put("classification", view.classification().name());
        metadata.put("apply_masking", view.columnName() != null && applyMasking);
        recordAudit(AuditAction.DATA_CLASSIFICATION_TAG_ADDED, view.id(), caller, auditContext, metadata);
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.DATA_CLASSIFICATION_TAG,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on data_classification_tag {}", action, resourceId, ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
