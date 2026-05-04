package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.core.api.CreateUserCommand;
import com.partqam.accessflow.core.api.UpdateUserCommand;
import com.partqam.accessflow.core.api.UserAdminService;
import com.partqam.accessflow.security.api.JwtClaims;
import com.partqam.accessflow.security.internal.token.RefreshTokenStore;
import com.partqam.accessflow.security.internal.web.model.AdminUserResponse;
import com.partqam.accessflow.security.internal.web.model.CreateUserRequest;
import com.partqam.accessflow.security.internal.web.model.UpdateUserRequest;
import com.partqam.accessflow.security.internal.web.model.UserPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Users", description = "User management endpoints (ADMIN only)")
@RequiredArgsConstructor
class AdminUserController {

    private final UserAdminService userAdminService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;

    @GetMapping
    @Operation(summary = "List users in the caller's organization (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of users")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    UserPageResponse listUsers(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = userAdminService.listUsers(caller.organizationId(), pageable)
                .map(AdminUserResponse::from);
        return UserPageResponse.from(page);
    }

    @PostMapping
    @Operation(summary = "Create a new local user in the caller's organization")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Email already exists")
    ResponseEntity<AdminUserResponse> createUser(@Valid @RequestBody CreateUserRequest request,
                                                 Authentication authentication) {
        var caller = currentClaims(authentication);
        var command = new CreateUserCommand(
                caller.organizationId(),
                request.email(),
                request.displayName(),
                passwordEncoder.encode(request.password()),
                request.role()
        );
        var created = userAdminService.createUser(command);
        var response = AdminUserResponse.from(created);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update role, active flag, or display name for a user")
    @ApiResponse(responseCode = "200", description = "User updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "User not found in caller's organization")
    @ApiResponse(responseCode = "422", description = "Illegal user operation (e.g. self-demote)")
    AdminUserResponse updateUser(@PathVariable UUID id,
                                 @Valid @RequestBody UpdateUserRequest request,
                                 Authentication authentication) {
        var caller = currentClaims(authentication);
        var command = new UpdateUserCommand(request.role(), request.active(), request.displayName());
        var updated = userAdminService.updateUser(id, caller.organizationId(),
                caller.userId(), command);
        return AdminUserResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a user and revoke all of their refresh tokens")
    @ApiResponse(responseCode = "204", description = "User deactivated")
    @ApiResponse(responseCode = "404", description = "User not found in caller's organization")
    @ApiResponse(responseCode = "422", description = "Cannot deactivate self")
    ResponseEntity<Void> deactivateUser(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        userAdminService.deactivateUser(id, caller.organizationId(), caller.userId());
        refreshTokenStore.revokeAllForUser(id.toString());
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
