package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.OrganizationDataClassificationListResponse;
import com.bablsoft.accessflow.security.internal.web.model.OrganizationDataClassificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/data-classifications")
@Tag(name = "Data classifications (reporting)",
        description = "Org-wide view of every data-classification tag across datasources, for "
                + "compliance reporting consumers.")
@PreAuthorize("hasAuthority('PERM_DATA_CLASSIFICATION_MANAGE')")
@RequiredArgsConstructor
class AdminDataClassificationController {

    private final DataClassificationAdminService dataClassificationAdminService;

    @GetMapping
    @Operation(summary = "List every data-classification tag in the organization")
    @ApiResponse(responseCode = "200", description = "All classification tags in the organization")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    OrganizationDataClassificationListResponse list(Authentication authentication) {
        var caller = currentClaims(authentication);
        var tags = dataClassificationAdminService.listForOrganization(caller.organizationId()).stream()
                .map(OrganizationDataClassificationResponse::from)
                .toList();
        return new OrganizationDataClassificationListResponse(tags);
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
