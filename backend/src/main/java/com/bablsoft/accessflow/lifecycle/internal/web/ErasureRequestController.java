package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestService;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestService.SubmitErasureCommand;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/lifecycle/erasure-requests")
@Tag(name = "Erasure Requests", description = "Self-service right-to-erasure requests")
@RequiredArgsConstructor
class ErasureRequestController {

    private final ErasureRequestService erasureRequestService;
    private final LifecycleAuditWriter auditWriter;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit a right-to-erasure request (async AI scope detection follows)")
    @ApiResponse(responseCode = "202", description = "Request accepted; scope detection runs asynchronously")
    @ApiResponse(responseCode = "400", description = "Validation error")
    ErasureRequestResponse submit(@Valid @RequestBody SubmitErasureRequestBody body,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = erasureRequestService.submit(new SubmitErasureCommand(
                caller.organizationId(), body.datasourceId(), body.subjectType(),
                body.subjectIdentifier(), body.reason(), caller.userId()));
        auditWriter.record(AuditAction.DATA_ERASURE_REQUESTED, AuditResourceType.DELETION_REQUEST,
                view.id(), caller, metadata(view), auditContext);
        return ErasureRequestResponse.from(view);
    }

    @GetMapping
    @Operation(summary = "List the caller's own erasure requests")
    @ApiResponse(responseCode = "200", description = "Page of the caller's erasure requests")
    ErasureRequestPageResponse listMine(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = erasureRequestService.listMine(caller.organizationId(), caller.userId(),
                SpringPageableAdapter.toPageRequest(pageable));
        return ErasureRequestPageResponse.from(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of the caller's erasure requests")
    @ApiResponse(responseCode = "200", description = "The erasure request")
    @ApiResponse(responseCode = "404", description = "Deletion request not found")
    ErasureRequestResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return ErasureRequestResponse.from(
                erasureRequestService.get(id, caller.organizationId()));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel an erasure request that has not yet been decided")
    @ApiResponse(responseCode = "204", description = "Request cancelled")
    @ApiResponse(responseCode = "404", description = "Deletion request not found")
    @ApiResponse(responseCode = "409", description = "Request is not in a cancellable state")
    void cancel(@PathVariable UUID id, Authentication authentication,
                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        erasureRequestService.cancel(id, caller.userId(), caller.organizationId());
        auditWriter.record(AuditAction.DATA_ERASURE_CANCELLED, AuditResourceType.DELETION_REQUEST,
                id, caller, Map.of(), auditContext);
    }

    private static Map<String, Object> metadata(ErasureRequestView view) {
        return Map.of(
                "datasourceId", view.datasourceId().toString(),
                "subjectType", view.subjectType().name(),
                "subjectIdentifier", view.subjectIdentifier());
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
