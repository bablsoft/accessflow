package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateReviewPlanCommand;
import com.bablsoft.accessflow.core.api.ReviewPlanAdminService;
import com.bablsoft.accessflow.core.api.UpdateReviewPlanCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateReviewPlanRequest;
import com.bablsoft.accessflow.security.internal.web.model.ReviewPlanApproverDto;
import com.bablsoft.accessflow.security.internal.web.model.ReviewPlanListResponse;
import com.bablsoft.accessflow.security.internal.web.model.ReviewPlanResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateReviewPlanRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review-plans")
@Tag(name = "Review Plans", description = "Review plan configuration endpoints")
@RequiredArgsConstructor
@Slf4j
class ReviewPlanController {

    private final ReviewPlanAdminService reviewPlanAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List review plans for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Review plans for the org")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    ReviewPlanListResponse list(Authentication authentication) {
        var caller = currentClaims(authentication);
        var plans = reviewPlanAdminService.list(caller.organizationId()).stream()
                .map(ReviewPlanResponse::from)
                .toList();
        return new ReviewPlanListResponse(plans);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single review plan by id")
    @ApiResponse(responseCode = "200", description = "Review plan details")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "404", description = "Review plan not found in caller's org")
    ReviewPlanResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return ReviewPlanResponse.from(reviewPlanAdminService.get(id, caller.organizationId()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new review plan")
    @ApiResponse(responseCode = "201", description = "Review plan created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Only admins can create review plans")
    @ApiResponse(responseCode = "422", description = "Approver configuration invalid or name conflict")
    ResponseEntity<ReviewPlanResponse> create(@Valid @RequestBody CreateReviewPlanRequest request,
                                              Authentication authentication,
                                              HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var command = new CreateReviewPlanCommand(
                caller.organizationId(),
                request.name(),
                request.description(),
                request.requiresAiReview(),
                request.requiresHumanApproval(),
                request.minApprovalsRequired(),
                request.approvalTimeoutHours(),
                request.autoApproveReads(),
                request.notifyChannels(),
                toRules(request.approvers()));
        var view = reviewPlanAdminService.create(command);
        recordAudit(AuditAction.REVIEW_PLAN_CREATED, view.id(), caller, httpRequest,
                Map.of("name", view.name()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(ReviewPlanResponse.from(view));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an existing review plan")
    @ApiResponse(responseCode = "200", description = "Review plan updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Only admins can update review plans")
    @ApiResponse(responseCode = "404", description = "Review plan not found")
    @ApiResponse(responseCode = "422", description = "Approver configuration invalid or name conflict")
    ReviewPlanResponse update(@PathVariable UUID id,
                              @Valid @RequestBody UpdateReviewPlanRequest request,
                              Authentication authentication,
                              HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var command = new UpdateReviewPlanCommand(
                request.name(),
                request.description(),
                request.requiresAiReview(),
                request.requiresHumanApproval(),
                request.minApprovalsRequired(),
                request.approvalTimeoutHours(),
                request.autoApproveReads(),
                request.notifyChannels(),
                toRules(request.approvers()));
        var view = reviewPlanAdminService.update(id, caller.organizationId(), command);
        recordAudit(AuditAction.REVIEW_PLAN_UPDATED, view.id(), caller, httpRequest,
                Map.of("name", view.name()));
        return ReviewPlanResponse.from(view);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a review plan that is not attached to any datasource")
    @ApiResponse(responseCode = "204", description = "Review plan deleted")
    @ApiResponse(responseCode = "403", description = "Only admins can delete review plans")
    @ApiResponse(responseCode = "404", description = "Review plan not found")
    @ApiResponse(responseCode = "409", description = "Review plan still attached to datasources")
    ResponseEntity<Void> delete(@PathVariable UUID id,
                                Authentication authentication,
                                HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        reviewPlanAdminService.delete(id, caller.organizationId());
        recordAudit(AuditAction.REVIEW_PLAN_DELETED, id, caller, httpRequest, Map.of());
        return ResponseEntity.noContent().build();
    }

    private static List<com.bablsoft.accessflow.core.api.ReviewPlanView.ApproverRule> toRules(
            List<ReviewPlanApproverDto> approvers) {
        if (approvers == null) {
            return null;
        }
        return approvers.stream().map(ReviewPlanApproverDto::toRule).toList();
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             HttpServletRequest httpRequest, Map<String, Object> metadata) {
        try {
            var context = RequestAuditContext.from(httpRequest);
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.REVIEW_PLAN,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on review plan {}", action, resourceId, ex);
        }
    }
}
