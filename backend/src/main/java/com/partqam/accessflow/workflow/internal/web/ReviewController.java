package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.security.api.JwtClaims;
import com.partqam.accessflow.workflow.api.ReviewService;
import com.partqam.accessflow.workflow.api.ReviewService.ReviewerContext;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "Reviewer workflow endpoints")
@RequiredArgsConstructor
class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "List queries the caller can act on as a reviewer")
    @ApiResponse(responseCode = "200", description = "Page of currently-actionable pending queries")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is not a reviewer")
    PendingReviewsPageResponse listPending(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = reviewService.listPendingForReviewer(toContext(caller), pageable)
                .map(PendingReviewItem::from);
        return PendingReviewsPageResponse.from(page);
    }

    @PostMapping("/{queryId}/approve")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Record an approval for a query awaiting review")
    @ApiResponse(responseCode = "200", description = "Decision recorded; resulting status returned")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter, not a reviewer, or not an eligible approver")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is not in PENDING_REVIEW")
    ReviewDecisionResponse approve(@PathVariable UUID queryId,
                                   @Valid @RequestBody ReviewDecisionRequest body,
                                   Authentication authentication) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.approve(queryId, toContext(caller), body.comment());
        return ReviewDecisionResponse.from(queryId, outcome);
    }

    @PostMapping("/{queryId}/reject")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Reject a query awaiting review")
    @ApiResponse(responseCode = "200", description = "Decision recorded; query transitioned to REJECTED")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter, not a reviewer, or not an eligible approver")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is not in PENDING_REVIEW")
    ReviewDecisionResponse reject(@PathVariable UUID queryId,
                                  @Valid @RequestBody ReviewDecisionRequest body,
                                  Authentication authentication) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.reject(queryId, toContext(caller), body.comment());
        return ReviewDecisionResponse.from(queryId, outcome);
    }

    @PostMapping("/{queryId}/request-changes")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Request changes from the submitter; query stays PENDING_REVIEW")
    @ApiResponse(responseCode = "200", description = "Decision recorded; status unchanged")
    @ApiResponse(responseCode = "400", description = "Validation error (comment is required)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter, not a reviewer, or not an eligible approver")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is not in PENDING_REVIEW")
    ReviewDecisionResponse requestChanges(@PathVariable UUID queryId,
                                          @Valid @RequestBody ReviewChangeRequestRequest body,
                                          Authentication authentication) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.requestChanges(queryId, toContext(caller), body.comment());
        return ReviewDecisionResponse.from(queryId, outcome);
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static ReviewerContext toContext(JwtClaims caller) {
        return new ReviewerContext(caller.userId(), caller.organizationId(), caller.role());
    }
}
