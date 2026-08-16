package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.ReviewDelegationFilter;
import com.bablsoft.accessflow.core.api.ReviewDelegationService;
import com.bablsoft.accessflow.security.internal.web.model.ReviewDelegationPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Org-wide read of who has delegated review duty to whom (#622) — the oversight surface an auditor
 * needs to interpret an {@code on_behalf_of} entry in the decision trail.
 */
@RestController
@RequestMapping("/api/v1/admin/review-delegations")
@Tag(name = "Review delegation", description = "Out-of-office delegation of review duty")
@RequiredArgsConstructor
class AdminReviewDelegationController {

    private final ReviewDelegationService reviewDelegationService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_QUERY_ADMIN')")
    @Operation(summary = "List every review delegation in the organization")
    @ApiResponse(responseCode = "200", description = "Delegations returned")
    @ApiResponse(responseCode = "403", description = "Caller lacks QUERY_ADMIN")
    ReviewDelegationPageResponse list(
            @RequestParam(name = "delegator_id", required = false) UUID delegatorId,
            @RequestParam(name = "delegate_id", required = false) UUID delegateId,
            @RequestParam(name = "active_only", defaultValue = "false") boolean activeOnly,
            Pageable pageable,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        return ReviewDelegationPageResponse.from(reviewDelegationService.listForOrganization(
                organizationId,
                new ReviewDelegationFilter(delegatorId, delegateId, activeOnly),
                SpringPageableAdapter.toPageRequest(pageable)));
    }
}
