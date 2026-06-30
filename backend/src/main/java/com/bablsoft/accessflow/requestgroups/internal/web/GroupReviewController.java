package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.requestgroups.api.GroupReviewService;
import com.bablsoft.accessflow.requestgroups.api.GroupReviewService.ReviewerContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/request-groups")
@Tag(name = "Request Group Reviews", description = "Approve or reject grouped requests as one element")
@RequiredArgsConstructor
class GroupReviewController {

    private final GroupReviewService groupReviewService;

    @GetMapping("/reviews")
    @Operation(summary = "List grouped requests pending the caller's review")
    @ApiResponse(responseCode = "200", description = "Page of pending group reviews")
    PendingGroupReviewResponse.Page pending(Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        return PendingGroupReviewResponse.Page.from(groupReviewService.listPending(context(caller),
                SpringPageableAdapter.toPageRequest(pageable)));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a grouped request (submitter can never self-approve)")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "403", description = "Self-approval or ineligible reviewer")
    GroupDecisionResponse approve(@PathVariable UUID id, @Valid @RequestBody GroupDecisionRequest body,
                                  Authentication authentication) {
        var caller = claims(authentication);
        return GroupDecisionResponse.from(groupReviewService.approve(id, context(caller), body.comment()));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a grouped request")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    GroupDecisionResponse reject(@PathVariable UUID id, @Valid @RequestBody GroupDecisionRequest body,
                                 Authentication authentication) {
        var caller = claims(authentication);
        return GroupDecisionResponse.from(groupReviewService.reject(id, context(caller), body.comment()));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static ReviewerContext context(JwtClaims caller) {
        return new ReviewerContext(caller.userId(), caller.organizationId(), caller.role());
    }
}
