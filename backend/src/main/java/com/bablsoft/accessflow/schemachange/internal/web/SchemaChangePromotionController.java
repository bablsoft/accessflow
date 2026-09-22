package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Schema Change Promotions",
        description = "Promote governed DDL change sets along a deployment pipeline's environment ladder (epic #870)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')")
class SchemaChangePromotionController {

    private final SchemaChangePromotionService promotionService;

    @PostMapping("/schema-change-sets/{id}/promotions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Promote a change set to one environment of its pipeline as an ordered request group")
    @ApiResponse(responseCode = "202", description = "Promotion accepted; the request group is under AI analysis")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "The caller holds no can_ddl grant on the target datasource")
    @ApiResponse(responseCode = "404", description = "Change set or environment not found in this organization")
    @ApiResponse(responseCode = "409", description = "Archived or empty set, ladder not satisfied, freeze window "
            + "in effect, bound datasource missing, or an open promotion already exists")
    @ApiResponse(responseCode = "422", description = "The environment binds no datasource, or requires a review "
            + "the target datasource's plan cannot enforce")
    SchemaChangePromotionResponse promote(@PathVariable UUID id, @Valid @RequestBody PromoteSchemaChangeSetRequest body,
                                          Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        return SchemaChangePromotionResponse.from(promotionService.promote(caller.organizationId(), caller.userId(),
                id, body.toCommand(auditContext.ipAddress(), auditContext.userAgent())));
    }

    @GetMapping("/schema-change-sets/{id}/promotions")
    @Operation(summary = "List a change set's promotions, newest first")
    @ApiResponse(responseCode = "200", description = "Promotions")
    @ApiResponse(responseCode = "404", description = "Change set not found")
    List<SchemaChangePromotionResponse> listForChangeSet(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return promotionService.listForChangeSet(caller.organizationId(), id).stream()
                .map(SchemaChangePromotionResponse::from)
                .toList();
    }

    @GetMapping("/schema-change-promotions/{id}")
    @Operation(summary = "Get one promotion, including its post-apply schema snapshot once applied")
    @ApiResponse(responseCode = "200", description = "Promotion")
    @ApiResponse(responseCode = "404", description = "Promotion not found")
    SchemaChangePromotionResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return SchemaChangePromotionResponse.from(promotionService.get(caller.organizationId(), id));
    }

    @PostMapping("/schema-change-promotions/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel a promotion whose request group has not yet been approved")
    @ApiResponse(responseCode = "204", description = "Promotion cancelled")
    @ApiResponse(responseCode = "404", description = "Promotion not found")
    @ApiResponse(responseCode = "409", description = "Promotion is terminal or its group can no longer be cancelled")
    void cancel(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        promotionService.cancel(caller.organizationId(), caller.userId(), id);
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
