package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiReviewService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/api-reviews")
@Tag(name = "API Reviews", description = "Approve or reject governed API calls")
@RequiredArgsConstructor
class ApiReviewController {

    private final ApiReviewService reviewService;
    private final ApiGovAuditWriter auditWriter;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_API_REQUEST_REVIEW')")
    @Operation(summary = "List API requests awaiting the caller's review")
    @ApiResponse(responseCode = "200", description = "Page of pending API reviews")
    PendingApiReviewResponse.Page pending(Authentication authentication, Pageable pageable,
                                          @RequestParam(name = "connector_id", required = false) UUID connectorId,
                                          @RequestParam(required = false) String verb) {
        var caller = claims(authentication);
        var filter = new ApiReviewService.PendingApiReviewFilter(connectorId, normalizeVerb(verb));
        return PendingApiReviewResponse.Page.from(reviewService.listPending(context(caller), filter,
                SpringPageableAdapter.toPageRequest(pageable)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_API_REQUEST_REVIEW')")
    @Operation(summary = "Approve an API request (submitter can never self-approve)")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "403", description = "Self-approval is forbidden")
    @ApiResponse(responseCode = "409", description = "Request is not awaiting review")
    ApiDecisionResponse approve(@PathVariable UUID id, @Valid @RequestBody ApiDecisionRequest body,
                                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var outcome = reviewService.approve(id, context(caller), body.comment());
        auditWriter.record(AuditAction.API_REQUEST_APPROVED, AuditResourceType.API_REQUEST, id, caller,
                new HashMap<>(), auditContext);
        return ApiDecisionResponse.from(outcome);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_API_REQUEST_REVIEW')")
    @Operation(summary = "Reject an API request")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "403", description = "Self-approval is forbidden")
    @ApiResponse(responseCode = "409", description = "Request is not awaiting review")
    ApiDecisionResponse reject(@PathVariable UUID id, @Valid @RequestBody ApiDecisionRequest body,
                               Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var outcome = reviewService.reject(id, context(caller), body.comment());
        auditWriter.record(AuditAction.API_REQUEST_REJECTED, AuditResourceType.API_REQUEST, id, caller,
                new HashMap<>(), auditContext);
        return ApiDecisionResponse.from(outcome);
    }

    private static String normalizeVerb(String verb) {
        return verb == null || verb.isBlank() ? null : verb.trim().toUpperCase(Locale.ROOT);
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static ApiReviewService.ReviewerContext context(JwtClaims caller) {
        return new ApiReviewService.ReviewerContext(caller.userId(), caller.organizationId(),
                caller.roleName(), caller.permissions());
    }
}
