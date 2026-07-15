package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.internal.web.model.PermissionCatalogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the code-defined permission catalog (AF-522). The catalog is fixed at build
 * time — admins compose roles from it but can never add or edit permissions.
 */
@RestController
@RequestMapping("/api/v1/admin/permissions")
@Tag(name = "Roles", description = "Custom role management endpoints")
@PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
class PermissionCatalogController {

    @GetMapping
    @Operation(summary = "List the fixed functional-permission catalog, grouped for display")
    @ApiResponse(responseCode = "200", description = "The permission catalog")
    PermissionCatalogResponse catalog() {
        return PermissionCatalogResponse.catalog();
    }
}
