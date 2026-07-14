package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.CollaboratorContext;
import com.bablsoft.accessflow.workflow.api.NewCommentInput;
import com.bablsoft.accessflow.workflow.api.QueryCommentService;
import com.bablsoft.accessflow.workflow.api.QueryCommentView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inline collaboration comment threads anchored to a query's SQL while it is in a co-authorable
 * state. Authorization (submitter / eligible reviewer / admin) lives in the service; mutations are
 * audited here with the caller's IP and User-Agent.
 */
@RestController
@RequestMapping("/api/v1/queries/{id}/comments")
@Tag(name = "Query Comments", description = "Inline collaboration comments for queries in review")
@RequiredArgsConstructor
@Slf4j
class QueryCommentController {

    private final QueryCommentService queryCommentService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List the comment threads on a query in the caller's organization")
    @ApiResponse(responseCode = "200", description = "Comment threads, oldest first")
    @ApiResponse(responseCode = "403", description = "Caller may not collaborate on this query")
    @ApiResponse(responseCode = "404", description = "Query not found in caller's organization")
    List<CommentThreadResponse> list(@PathVariable UUID id, Authentication authentication) {
        var threads = queryCommentService.listThreads(id, context(authentication));
        return threads.stream().map(CommentThreadResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a new comment thread anchored to a line range of the query's SQL")
    @ApiResponse(responseCode = "201", description = "Comment created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller may not collaborate on this query")
    @ApiResponse(responseCode = "404", description = "Query not found in caller's organization")
    CommentResponse create(@PathVariable UUID id, @Valid @RequestBody CreateCommentRequest body,
                           Authentication authentication, RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var view = queryCommentService.addComment(id, context(caller), new NewCommentInput(
                body.anchorStartLine(), body.anchorEndLine(), body.anchorSnapshot(), body.body()));
        recordAudit(caller, id, view.id(), AuditAction.QUERY_COMMENT_ADDED, auditContext);
        return CommentResponse.from(view);
    }

    @PostMapping("/{commentId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reply to an existing comment thread")
    @ApiResponse(responseCode = "201", description = "Reply created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller may not collaborate on this query")
    @ApiResponse(responseCode = "404", description = "Query or parent comment not found")
    CommentResponse reply(@PathVariable UUID id, @PathVariable UUID commentId,
                          @Valid @RequestBody ReplyCommentRequest body, Authentication authentication,
                          RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var view = queryCommentService.reply(id, commentId, context(caller), body.body());
        recordAudit(caller, id, view.id(), AuditAction.QUERY_COMMENT_REPLIED, auditContext);
        return CommentResponse.from(view);
    }

    @PostMapping("/{commentId}/resolve")
    @Operation(summary = "Resolve a comment thread")
    @ApiResponse(responseCode = "200", description = "Thread resolved")
    @ApiResponse(responseCode = "403", description = "Caller may not collaborate on this query")
    @ApiResponse(responseCode = "404", description = "Query or comment not found")
    CommentResponse resolve(@PathVariable UUID id, @PathVariable UUID commentId,
                            Authentication authentication, RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var view = queryCommentService.resolve(id, commentId, context(caller));
        recordAudit(caller, id, view.id(), AuditAction.QUERY_COMMENT_RESOLVED, auditContext);
        return CommentResponse.from(view);
    }

    @PostMapping("/{commentId}/reopen")
    @Operation(summary = "Reopen a resolved comment thread")
    @ApiResponse(responseCode = "200", description = "Thread reopened")
    @ApiResponse(responseCode = "403", description = "Caller may not collaborate on this query")
    @ApiResponse(responseCode = "404", description = "Query or comment not found")
    CommentResponse reopen(@PathVariable UUID id, @PathVariable UUID commentId,
                           Authentication authentication, RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var view = queryCommentService.reopen(id, commentId, context(caller));
        recordAudit(caller, id, view.id(), AuditAction.QUERY_COMMENT_REOPENED, auditContext);
        return CommentResponse.from(view);
    }

    private static CollaboratorContext context(Authentication authentication) {
        return context((JwtClaims) authentication.getPrincipal());
    }

    private static CollaboratorContext context(JwtClaims caller) {
        return new CollaboratorContext(caller.userId(), caller.organizationId(), caller.roleName(),
                caller.permissions());
    }

    private void recordAudit(JwtClaims caller, UUID queryId, UUID commentId, AuditAction action,
                             RequestAuditContext auditContext) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("query_id", queryId.toString());
            metadata.put("comment_id", commentId.toString());
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.QUERY_COMMENT,
                    commentId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on query comment {}", action, commentId, ex);
        }
    }
}
