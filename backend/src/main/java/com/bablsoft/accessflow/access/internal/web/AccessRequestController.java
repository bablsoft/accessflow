package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestService;
import com.bablsoft.accessflow.access.api.AccessRequestService.SubmitCommand;
import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/access-requests")
@Tag(name = "Access Requests", description = "Just-in-time time-bound access requests")
@RequiredArgsConstructor
class AccessRequestController {

    private final AccessRequestService accessRequestService;
    private final AccessRequestAuditWriter auditWriter;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a time-boxed access-grant request for review")
    @ApiResponse(responseCode = "201", description = "Request created in PENDING")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "404", description = "Datasource not found in the caller's organization")
    @ApiResponse(responseCode = "422", description = "Requested duration outside the allowed range")
    AccessRequestResponse submit(@Valid @RequestBody SubmitAccessRequestBody body,
                                 Authentication authentication,
                                 RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new SubmitCommand(caller.organizationId(), caller.userId(),
                body.datasourceId(), Boolean.TRUE.equals(body.canRead()),
                Boolean.TRUE.equals(body.canWrite()), Boolean.TRUE.equals(body.canDdl()),
                body.allowedSchemas(), body.allowedTables(), body.requestedDuration(),
                body.justification(), Boolean.TRUE.equals(body.preApproveQueries()));
        var view = accessRequestService.submit(command);
        auditWriter.record(AuditAction.ACCESS_REQUEST_SUBMITTED, view.id(), caller,
                submitMetadata(view), auditContext);
        return AccessRequestResponse.from(view);
    }

    @GetMapping
    @Operation(summary = "List the caller's own access requests")
    @ApiResponse(responseCode = "200", description = "Page of the caller's access requests")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    AccessRequestPageResponse listMine(@RequestParam(required = false) AccessGrantStatus status,
                                       Authentication authentication,
                                       Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = accessRequestService.listMine(caller.organizationId(), caller.userId(), status,
                SpringPageableAdapter.toPageRequest(pageable));
        return AccessRequestPageResponse.from(page);
    }

    @GetMapping("/datasources")
    @Operation(summary = "List datasources the caller can request access to (id + name only)")
    @ApiResponse(responseCode = "200", description = "Active datasources in the organization")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    List<RequestableDatasourceResponse> listRequestableDatasources(Authentication authentication) {
        var caller = currentClaims(authentication);
        return accessRequestService.listRequestableDatasources(caller.organizationId()).stream()
                .map(RequestableDatasourceResponse::from)
                .toList();
    }

    @GetMapping("/datasources/{id}/schema")
    @Operation(summary = "Introspect schema + table names of a requestable datasource (no permission required)")
    @ApiResponse(responseCode = "200", description = "Schema and table names for the access-request form")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "404", description = "Datasource not found in the caller's organization")
    @ApiResponse(responseCode = "422", description = "Schema introspection failed")
    RequestableSchemaResponse getRequestableDatasourceSchema(@PathVariable UUID id,
                                                             Authentication authentication) {
        var caller = currentClaims(authentication);
        return RequestableSchemaResponse.from(
                accessRequestService.introspectRequestableDatasourceSchema(id, caller.organizationId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel the caller's own pending access request")
    @ApiResponse(responseCode = "204", description = "Cancelled")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "404", description = "Request not found or not owned by the caller")
    @ApiResponse(responseCode = "409", description = "Request is not in PENDING")
    void cancel(@PathVariable UUID id, Authentication authentication,
                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        accessRequestService.cancel(id, caller.userId(), caller.organizationId());
        auditWriter.record(AuditAction.ACCESS_REQUEST_CANCELLED, id, caller, Map.of(), auditContext);
    }

    private static Map<String, Object> submitMetadata(AccessRequestView view) {
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", view.datasourceId().toString());
        metadata.put("requested_duration", view.requestedDuration());
        metadata.put("can_read", view.canRead());
        metadata.put("can_write", view.canWrite());
        metadata.put("can_ddl", view.canDdl());
        metadata.put("pre_approve_queries", view.preApproveQueries());
        return metadata;
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
