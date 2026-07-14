package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupListFilter;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.SubmitRequestGroupCommand;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/request-groups")
@Tag(name = "Request Groups", description = "Bundle ordered query/API members into one reviewed group")
@RequiredArgsConstructor
class RequestGroupController {

    private final RequestGroupService requestGroupService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a DRAFT request group with ordered members")
    @ApiResponse(responseCode = "201", description = "Draft created")
    @ApiResponse(responseCode = "403", description = "No permission on a member target")
    RequestGroupResponse create(@Valid @RequestBody CreateRequestGroupRequest body,
                                Authentication authentication) {
        var caller = claims(authentication);
        return RequestGroupResponse.from(requestGroupService.createDraft(
                body.toCommand(caller.organizationId(), caller.userId(), isAdmin(caller))));
    }

    @GetMapping
    @Operation(summary = "List request groups (admins see all; others see their own)")
    @ApiResponse(responseCode = "200", description = "Page of groups")
    RequestGroupPageResponse list(Authentication authentication, Pageable pageable,
                                  @RequestParam(required = false) RequestGroupStatus status,
                                  @RequestParam(name = "submitted_by", required = false) UUID submittedByParam) {
        var caller = claims(authentication);
        var submittedBy = isAdmin(caller) ? submittedByParam : caller.userId();
        var filter = new RequestGroupListFilter(caller.organizationId(), submittedBy, status);
        return RequestGroupPageResponse.from(requestGroupService.list(filter,
                SpringPageableAdapter.toPageRequest(pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a request group with its ordered members")
    @ApiResponse(responseCode = "200", description = "The group")
    @ApiResponse(responseCode = "404", description = "Not found")
    RequestGroupResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return RequestGroupResponse.from(requestGroupService.get(id, caller.organizationId(),
                caller.userId(), isAdmin(caller)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a DRAFT group's fields and members")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "409", description = "Group is not a DRAFT")
    RequestGroupResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequestGroupRequest body,
                                Authentication authentication) {
        var caller = claims(authentication);
        return RequestGroupResponse.from(requestGroupService.updateDraft(
                body.toCommand(id, caller.organizationId(), caller.userId(), isAdmin(caller))));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a DRAFT request group")
    @ApiResponse(responseCode = "204", description = "Deleted")
    void delete(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        requestGroupService.deleteDraft(id, caller.organizationId(), caller.userId());
    }

    @PostMapping("/{id}/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit a DRAFT group for AI + review (or break-glass force-approve)")
    @ApiResponse(responseCode = "202", description = "Accepted for governance")
    @ApiResponse(responseCode = "403", description = "Break-glass requires can_break_glass on every target")
    RequestGroupResponse submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequestGroupRequest body,
                                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        requestGroupService.submit(new SubmitRequestGroupCommand(id, caller.organizationId(),
                caller.userId(), isAdmin(caller), body.breakGlass(), body.scheduledFor(),
                auditContext.ipAddress(), auditContext.userAgent()));
        return RequestGroupResponse.from(requestGroupService.get(id, caller.organizationId(),
                caller.userId(), isAdmin(caller)));
    }

    @PostMapping("/{id}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Run an APPROVED group's members in order")
    @ApiResponse(responseCode = "202", description = "Executed")
    RequestGroupResponse execute(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return RequestGroupResponse.from(requestGroupService.execute(id, caller.organizationId(),
                caller.userId(), isAdmin(caller)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending or scheduled-approved group (submitter only)")
    @ApiResponse(responseCode = "200", description = "Cancelled")
    RequestGroupResponse cancel(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        requestGroupService.cancel(id, caller.organizationId(), caller.userId());
        return RequestGroupResponse.from(requestGroupService.get(id, caller.organizationId(),
                caller.userId(), isAdmin(caller)));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static boolean isAdmin(JwtClaims caller) {
        return caller.has(Permission.QUERY_ADMIN);
    }
}
