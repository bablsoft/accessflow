package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.DataBudgetStatusService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.DataBudgetStatusListResponse;
import com.bablsoft.accessflow.security.internal.web.model.DataBudgetStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only data-budget standing (#942): the caller's own, and any user's for admins. */
@RestController
@Tag(name = "Data budgets", description = "Remaining data-volume allowance (#942)")
@RequiredArgsConstructor
class DataBudgetStatusController {

    private final DataBudgetStatusService dataBudgetStatusService;
    private final DatasourceAdminService datasourceAdminService;
    private final UserQueryService userQueryService;

    @GetMapping("/api/v1/datasources/{datasourceId}/data-budgets/me")
    @Operation(summary = "The caller's data-budget standing on a datasource")
    @ApiResponse(responseCode = "200", description = "Standing; an empty budgets list when none applies")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not visible to the caller")
    DataBudgetStatusResponse mine(@PathVariable UUID datasourceId, Authentication authentication) {
        var caller = currentClaims(authentication);
        // Resolves visibility first so an invisible datasource reads 404, never an empty 200.
        if (caller.has(Permission.QUERY_ADMIN) || caller.has(Permission.DATASOURCE_MANAGE)) {
            datasourceAdminService.getForAdmin(datasourceId, caller.organizationId());
        } else {
            datasourceAdminService.getForUser(datasourceId, caller.organizationId(),
                    caller.userId());
        }
        return DataBudgetStatusResponse.from(
                dataBudgetStatusService.statusFor(datasourceId, caller.userId()));
    }

    @GetMapping("/api/v1/admin/users/{userId}/data-budget-usage")
    @PreAuthorize("hasAuthority('PERM_DATA_BUDGET_MANAGE') or hasAuthority('PERM_USER_MANAGE')")
    @Operation(summary = "A user's data-budget standing on every budgeted datasource")
    @ApiResponse(responseCode = "200", description = "One entry per datasource where a budget applies to the user")
    @ApiResponse(responseCode = "404", description = "User not found in the caller's organization")
    DataBudgetStatusListResponse forUser(@PathVariable UUID userId, Authentication authentication) {
        var caller = currentClaims(authentication);
        userQueryService.findById(userId)
                .filter(u -> caller.organizationId().equals(u.organizationId()))
                .orElseThrow(() -> new UserNotFoundException(userId));
        return new DataBudgetStatusListResponse(dataBudgetStatusService
                .statusesForUser(caller.organizationId(), userId).stream()
                .map(DataBudgetStatusResponse::from)
                .toList());
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
