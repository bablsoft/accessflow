package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.apigov.api.ApiRequestListFilter;
import com.bablsoft.accessflow.apigov.api.ApiRequestService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/api-requests")
@Tag(name = "API Requests", description = "Submit and track governed API calls")
@RequiredArgsConstructor
class ApiRequestController {

    private final ApiRequestService requestService;
    private final ApiAssistService assistService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit a governed API call (runs through AI → routing → review)")
    @ApiResponse(responseCode = "202", description = "Accepted for governance")
    @ApiResponse(responseCode = "403", description = "No permission on the connector")
    @ApiResponse(responseCode = "422", description = "Schema validation failed")
    ApiRequestResponse submit(@Valid @RequestBody SubmitApiRequestRequest body,
                              Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var result = requestService.submit(body.toCommand(caller.organizationId(), caller.userId(),
                isAdmin(caller), auditContext.ipAddress(), auditContext.userAgent()));
        return ApiRequestResponse.from(requestService.get(result.id(), caller.organizationId(),
                caller.userId(), caller.permissions()));
    }

    @GetMapping
    @Operation(summary = "List API requests (admins see all; others see their own)")
    @ApiResponse(responseCode = "200", description = "Page of requests")
    ApiRequestPageResponse list(Authentication authentication, Pageable pageable,
                                @RequestParam(required = false) QueryStatus status,
                                @RequestParam(name = "connector_id", required = false) UUID connectorId,
                                @RequestParam(required = false) String verb,
                                @RequestParam(name = "submitted_by", required = false) UUID submittedByParam,
                                @RequestParam(name = "trace_id", required = false) String traceId,
                                @RequestParam(name = "span_id", required = false) String spanId,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to) {
        var caller = claims(authentication);
        var pageRequest = SpringPageableAdapter.toPageRequest(pageable);
        // Non-admins are hard-scoped to their own requests; the submitted_by filter is admin-only.
        var submittedBy = isAdmin(caller) ? submittedByParam : caller.userId();
        var filter = new ApiRequestListFilter(caller.organizationId(), submittedBy, connectorId, status,
                normalizeVerb(verb), blankToNull(traceId), blankToNull(spanId), from, to);
        return ApiRequestPageResponse.from(requestService.list(filter, pageRequest));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an API request with its response snapshot and review decisions")
    @ApiResponse(responseCode = "200", description = "Request")
    @ApiResponse(responseCode = "404",
            description = "Not found in caller's org, or caller is not the submitter, a reviewer, or an admin")
    ApiRequestResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return ApiRequestResponse.from(requestService.get(id, caller.organizationId(), caller.userId(),
                caller.permissions()));
    }

    @GetMapping("/{id}/response")
    @Operation(summary = "Download the full stored response snapshot in its original content type")
    @ApiResponse(responseCode = "200", description = "Response body as an attachment")
    @ApiResponse(responseCode = "404", description = "Request not found")
    @ApiResponse(responseCode = "409", description = "Request has no stored response")
    ResponseEntity<byte[]> downloadResponse(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        var payload = requestService.downloadResponse(id, caller.organizationId(), caller.userId(),
                caller.permissions());
        var disposition = ContentDisposition.attachment().filename(payload.filename()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(parseMediaType(payload.contentType()))
                .body(payload.content());
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel a pending (or scheduled-and-approved) API request")
    @ApiResponse(responseCode = "204", description = "Cancelled")
    @ApiResponse(responseCode = "409", description = "Request is not cancellable")
    void cancel(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        requestService.cancel(id, caller.organizationId(), caller.userId());
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "Execute an APPROVED API request against the upstream target")
    @ApiResponse(responseCode = "200", description = "Executed (status reflects success/failure)")
    @ApiResponse(responseCode = "409", description = "Request is not APPROVED")
    ApiRequestResponse execute(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return ApiRequestResponse.from(requestService.execute(id, caller.organizationId(),
                caller.userId(), isAdmin(caller)));
    }

    @PostMapping("/analyze")
    @Operation(summary = "Debounced AI risk preview of a draft API call")
    @ApiResponse(responseCode = "200", description = "Risk preview")
    ApiAiPreviewResponse analyze(@Valid @RequestBody AnalyzeApiCallRequest body,
                                 Authentication authentication) {
        var caller = claims(authentication);
        var preview = assistService.analyze(body.connectorId(), caller.organizationId(), caller.userId(),
                isAdmin(caller), new ApiAssistService.AnalyzeInput(body.operationId(), body.verb(),
                        body.requestPath(), body.requestBody(), body.language()));
        return ApiAiPreviewResponse.from(preview);
    }

    @PostMapping("/generate")
    @Operation(summary = "Text-to-API: turn plain English into a draft call (schema connectors only)")
    @ApiResponse(responseCode = "200", description = "Draft call")
    @ApiResponse(responseCode = "422", description = "Text-to-API disabled or no schema")
    GeneratedApiCallResponse generate(@Valid @RequestBody GenerateApiCallRequest body,
                                      Authentication authentication) {
        var caller = claims(authentication);
        var draft = assistService.generate(body.connectorId(), caller.organizationId(), caller.userId(),
                isAdmin(caller), body.prompt(), body.language());
        return GeneratedApiCallResponse.from(draft);
    }

    private static String normalizeVerb(String verb) {
        return verb == null || verb.isBlank() ? null : verb.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static MediaType parseMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static boolean isAdmin(JwtClaims caller) {
        return caller.has(Permission.QUERY_ADMIN);
    }
}
