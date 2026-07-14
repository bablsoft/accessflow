package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.KnowledgeBaseService;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentView;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai-configs/{configId}/knowledge-documents")
@PreAuthorize("hasAuthority('PERM_AI_MANAGE')")
@Tag(name = "Admin RAG Knowledge Base",
        description = "Knowledge-base documents attached to a RAG-enabled AI configuration (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminKnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List the knowledge-base documents for an AI configuration")
    @ApiResponse(responseCode = "200", description = "Documents (content excluded)")
    @ApiResponse(responseCode = "404", description = "Configuration not found in this organization")
    List<KnowledgeDocumentResponse> list(@PathVariable UUID configId, Authentication authentication) {
        var caller = currentClaims(authentication);
        return knowledgeBaseService.list(configId, caller.organizationId()).stream()
                .map(KnowledgeDocumentResponse::from)
                .toList();
    }

    @PostMapping
    @Operation(summary = "Add a knowledge-base document — chunked, embedded and stored on ingestion")
    @ApiResponse(responseCode = "201", description = "Document ingested")
    @ApiResponse(responseCode = "400", description = "Validation error / RAG not enabled")
    @ApiResponse(responseCode = "404", description = "Configuration not found in this organization")
    @ApiResponse(responseCode = "502", description = "Embedding provider or vector store unreachable")
    ResponseEntity<KnowledgeDocumentResponse> create(@PathVariable UUID configId,
                                                     @Valid @RequestBody CreateKnowledgeDocumentRequest body,
                                                     Authentication authentication,
                                                     RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var created = knowledgeBaseService.create(configId, caller.organizationId(), body.toCommand());
        recordAudit(AuditAction.KNOWLEDGE_DOCUMENT_CREATED, created.id(), caller, auditContext,
                Map.of("ai_config_id", configId.toString(), "title", created.title(),
                        "chunk_count", created.chunkCount()));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(KnowledgeDocumentResponse.from(created));
    }

    @DeleteMapping("/{documentId}")
    @Operation(summary = "Delete a knowledge-base document and remove its stored chunks")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Configuration or document not found")
    ResponseEntity<Void> delete(@PathVariable UUID configId, @PathVariable UUID documentId,
                                Authentication authentication, RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        knowledgeBaseService.delete(documentId, configId, caller.organizationId());
        recordAudit(AuditAction.KNOWLEDGE_DOCUMENT_DELETED, documentId, caller, auditContext,
                Map.of("ai_config_id", configId.toString()));
        return ResponseEntity.noContent().build();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.KNOWLEDGE_DOCUMENT,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on knowledge_document {}", action, resourceId, ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
