package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.CreateReviewDelegationCommand;
import com.bablsoft.accessflow.core.api.ReviewDelegationService;
import com.bablsoft.accessflow.security.internal.web.model.CreateReviewDelegationRequest;
import com.bablsoft.accessflow.security.internal.web.model.DelegateCandidateResponse;
import com.bablsoft.accessflow.security.internal.web.model.ReviewDelegationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-service out-of-office review delegation (#622). Available to any authenticated user — naming
 * a delegate is not itself a privileged act, and a delegation from someone with no review rights
 * simply resolves to no eligibility.
 */
@RestController
@RequestMapping("/api/v1/me/review-delegations")
@Tag(name = "Review delegation", description = "Out-of-office delegation of review duty")
@RequiredArgsConstructor
class MeReviewDelegationController {

    private final ReviewDelegationService reviewDelegationService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List delegations the caller granted and received")
    @ApiResponse(responseCode = "200", description = "Delegations returned")
    ReviewDelegationResponse.MyDelegations list(
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        return new ReviewDelegationResponse.MyDelegations(
                reviewDelegationService.listGrantedBy(organizationId, userId).stream()
                        .map(ReviewDelegationResponse::from).toList(),
                reviewDelegationService.listReceivedBy(organizationId, userId).stream()
                        .map(ReviewDelegationResponse::from).toList());
    }

    @GetMapping("/candidates")
    @Operation(summary = "List colleagues the caller may name as their delegate")
    @ApiResponse(responseCode = "200", description = "Candidates returned")
    List<DelegateCandidateResponse> candidates(
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        return reviewDelegationService.listDelegateCandidates(organizationId, userId).stream()
                .map(DelegateCandidateResponse::from).toList();
    }

    @PostMapping
    @Operation(summary = "Delegate review duty for a window")
    @ApiResponse(responseCode = "201", description = "Delegation created")
    @ApiResponse(responseCode = "422", description = "Delegate is the caller, or the scope does not resolve")
    ResponseEntity<ReviewDelegationResponse> create(
            @Valid @RequestBody CreateReviewDelegationRequest body,
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        var created = reviewDelegationService.create(new CreateReviewDelegationCommand(
                organizationId, userId, body.delegateUserId(), body.scopeKind(), body.scopeId(),
                body.reason(), body.startsAt(), body.endsAt()));
        audit(AuditAction.REVIEW_DELEGATION_CREATED, created.id(), organizationId, userId,
                metadata(created.delegateUserId(), created.scopeKind() == null
                        ? null : created.scopeKind().name(), created.scopeId()));
        return ResponseEntity.created(URI.create("/api/v1/me/review-delegations/" + created.id()))
                .body(ReviewDelegationResponse.from(created));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a delegation the caller granted")
    @ApiResponse(responseCode = "204", description = "Delegation revoked")
    @ApiResponse(responseCode = "404", description = "Not the caller's delegation, or unknown")
    ResponseEntity<Void> revoke(
            @PathVariable UUID id,
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        reviewDelegationService.revoke(id, organizationId, userId);
        audit(AuditAction.REVIEW_DELEGATION_REVOKED, id, organizationId, userId, Map.of());
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> metadata(UUID delegateId, String scopeKind, UUID scopeId) {
        var metadata = new HashMap<String, Object>();
        metadata.put("delegate_user_id", delegateId.toString());
        if (scopeKind != null) {
            metadata.put("scope_kind", scopeKind);
            metadata.put("scope_id", scopeId.toString());
        }
        return metadata;
    }

    private void audit(AuditAction action, UUID delegationId, UUID organizationId, UUID actorId,
                       Map<String, Object> metadata) {
        auditLogService.record(new AuditEntry(action, AuditResourceType.REVIEW_DELEGATION,
                delegationId, organizationId, actorId, metadata, null, null));
    }
}
