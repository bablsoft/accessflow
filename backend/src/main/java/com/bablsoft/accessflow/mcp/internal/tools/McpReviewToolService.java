package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpPage;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpPendingReview;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpReviewOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Reviewer-only MCP tools. {@link PreAuthorize} acts as a fast-path gate; the service-level
 * checks in {@code DefaultReviewService} (self-approval block, stage eligibility, org
 * membership) remain authoritative.
 */
@Service
@RequiredArgsConstructor
public class McpReviewToolService {

    private static final int MAX_PAGE_SIZE = 100;

    private final McpCurrentUser currentUser;
    private final ReviewService reviewService;

    @Tool(name = "list_pending_reviews",
            description = "List queries pending review at a stage the calling user is eligible to "
                    + "approve. Excludes queries the user themselves submitted. Reviewer or admin only.")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public McpPage<McpPendingReview> listPendingReviews(
            @ToolParam(required = false, description = "Zero-based page number (default 0).") Integer page,
            @ToolParam(required = false, description = "Page size (default 20, max 100).") Integer size) {
        var claims = currentUser.requireClaims();
        var pageRequest = pageRequest(page, size);
        var context = new ReviewService.ReviewerContext(claims.userId(), claims.organizationId(), claims.role());
        var result = reviewService.listPendingForReviewer(context, pageRequest);
        return McpPage.from(result, McpPendingReview::from);
    }

    @Tool(name = "review_query",
            description = "Record a review decision on a query that is PENDING_REVIEW. The decision "
                    + "must be APPROVED, REJECTED, or REQUESTED_CHANGES. Self-review is blocked. "
                    + "Reviewer or admin only.")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public McpReviewOutcome reviewQuery(
            @ToolParam(description = "Query request UUID to decide on.") UUID queryId,
            @ToolParam(description = "Decision: APPROVED, REJECTED, or REQUESTED_CHANGES.") String decision,
            @ToolParam(required = false, description = "Optional comment (max 4000 chars) shown alongside the decision.") String comment) {
        var claims = currentUser.requireClaims();
        var context = new ReviewService.ReviewerContext(claims.userId(), claims.organizationId(), claims.role());
        var decisionType = parseDecision(decision);
        if (comment != null && comment.length() > 4000) {
            throw new IllegalArgumentException("comment must be at most 4000 characters");
        }
        var outcome = switch (decisionType) {
            case APPROVED -> reviewService.approve(queryId, context, comment);
            case REJECTED -> reviewService.reject(queryId, context, comment);
            case REQUESTED_CHANGES -> reviewService.requestChanges(queryId, context, comment);
        };
        return McpReviewOutcome.from(outcome);
    }

    private static DecisionType parseDecision(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("decision is required (APPROVED, REJECTED, or REQUESTED_CHANGES)");
        }
        try {
            return DecisionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown decision '" + value + "'; expected one of APPROVED, REJECTED, REQUESTED_CHANGES");
        }
    }

    private static PageRequest pageRequest(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p, s);
    }
}
