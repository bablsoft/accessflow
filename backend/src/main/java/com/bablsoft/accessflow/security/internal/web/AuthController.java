package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.BootstrapService;
import com.bablsoft.accessflow.core.api.SetupCommand;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.security.api.AuthenticationService;
import com.bablsoft.accessflow.security.api.LoginCommand;
import com.bablsoft.accessflow.security.api.PasswordResetService;
import com.bablsoft.accessflow.security.api.TotpAuthenticationException;
import com.bablsoft.accessflow.security.api.TotpRequiredException;
import com.bablsoft.accessflow.security.api.UserInvitationService;
import com.bablsoft.accessflow.security.internal.web.model.AcceptInvitationRequest;
import com.bablsoft.accessflow.security.internal.web.model.ForgotPasswordRequest;
import com.bablsoft.accessflow.security.internal.web.model.InvitationPreviewResponse;
import com.bablsoft.accessflow.security.internal.web.model.LoginRequest;
import com.bablsoft.accessflow.security.internal.web.model.LoginResponse;
import com.bablsoft.accessflow.security.internal.web.model.PasswordResetPreviewResponse;
import com.bablsoft.accessflow.security.internal.web.model.ResetPasswordRequest;
import com.bablsoft.accessflow.security.internal.web.model.SetupRequest;
import com.bablsoft.accessflow.security.internal.web.model.SetupStatusResponse;
import com.bablsoft.accessflow.security.internal.web.model.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "JWT authentication endpoints")
@RequiredArgsConstructor
@Slf4j
class AuthController {

    private static final String REFRESH_TOKEN_COOKIE = RefreshCookieWriter.REFRESH_TOKEN_COOKIE;
    private static final int REFRESH_COOKIE_MAX_AGE = RefreshCookieWriter.REFRESH_COOKIE_MAX_AGE;

    private final AuthenticationService authenticationService;
    private final AuditLogService auditLogService;
    private final UserQueryService userQueryService;
    private final BootstrapService bootstrapService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshCookieWriter refreshCookieWriter;
    private final UserInvitationService userInvitationService;
    private final PasswordResetService passwordResetService;

    @GetMapping("/setup-status")
    @Operation(summary = "Report whether the deployment still needs first-time admin setup")
    @ApiResponse(responseCode = "200", description = "Setup status returned")
    @SecurityRequirements
    SetupStatusResponse setupStatus() {
        return new SetupStatusResponse(bootstrapService.isSetupRequired());
    }

    @PostMapping("/setup")
    @Operation(summary = "Create the first organization and admin user, then sign in (first-run only)")
    @ApiResponse(responseCode = "201", description = "Setup completed and user logged in")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Setup already completed or email already exists")
    @SecurityRequirements
    ResponseEntity<LoginResponse> setup(@Valid @RequestBody SetupRequest request,
                                        RequestAuditContext auditContext,
                                        HttpServletResponse response) {
        var passwordHash = passwordEncoder.encode(request.password());
        var result = bootstrapService.performSetup(new SetupCommand(
                request.organizationName(),
                request.email(),
                request.displayName(),
                passwordHash));
        recordSetupAudit(result.userId(), result.organizationId(), request.email(), auditContext);
        var login = authenticationService.login(
                new LoginCommand(request.email(), request.password(), null));
        setRefreshCookie(response, login.refreshToken(), REFRESH_COOKIE_MAX_AGE);
        return ResponseEntity.status(HttpStatus.CREATED).body(toLoginResponse(login));
    }

    @GetMapping("/invitations/{token}")
    @Operation(summary = "Preview an invitation (public, no auth)")
    @ApiResponse(responseCode = "200", description = "Invitation preview")
    @ApiResponse(responseCode = "404", description = "Invitation not found, expired, or revoked")
    @SecurityRequirements
    InvitationPreviewResponse previewInvitation(@PathVariable String token) {
        return InvitationPreviewResponse.from(userInvitationService.previewByToken(token));
    }

    @PostMapping("/invitations/{token}/accept")
    @Operation(summary = "Accept an invitation by setting a password (public, no auth)")
    @ApiResponse(responseCode = "201", description = "User created from the invitation")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Invitation not found, expired, or revoked")
    @ApiResponse(responseCode = "409", description = "Email already exists")
    @SecurityRequirements
    ResponseEntity<Void> acceptInvitation(@PathVariable String token,
                                          @Valid @RequestBody AcceptInvitationRequest request,
                                          RequestAuditContext auditContext) {
        var accepted = userInvitationService.acceptInvitation(token, request.password(),
                request.displayName());
        recordInvitationAcceptedAudit(accepted.userId(), accepted.organizationId(), auditContext);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/password/forgot")
    @Operation(summary = "Request a password reset email (public, no auth)")
    @ApiResponse(responseCode = "202", description = "Request accepted; an email is sent only if a matching active LOCAL account exists")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @SecurityRequirements
    ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                        RequestAuditContext auditContext) {
        passwordResetService.requestReset(request.email());
        recordPasswordResetRequestedAudit(request.email(), auditContext);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/password/reset/{token}")
    @Operation(summary = "Preview a password reset token (public, no auth)")
    @ApiResponse(responseCode = "200", description = "Token is valid; preview returned")
    @ApiResponse(responseCode = "404", description = "Token not found")
    @ApiResponse(responseCode = "422", description = "Token expired, used, or revoked")
    @SecurityRequirements
    PasswordResetPreviewResponse previewPasswordReset(@PathVariable String token) {
        return PasswordResetPreviewResponse.from(passwordResetService.previewByToken(token));
    }

    @PostMapping("/password/reset/{token}")
    @Operation(summary = "Reset the password for the given token (public, no auth)")
    @ApiResponse(responseCode = "204", description = "Password reset; all sessions revoked")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Token not found")
    @ApiResponse(responseCode = "422", description = "Token expired, used, or revoked")
    @SecurityRequirements
    ResponseEntity<Void> resetPassword(@PathVariable String token,
                                       @Valid @RequestBody ResetPasswordRequest request,
                                       RequestAuditContext auditContext) {
        var userId = passwordResetService.resetPassword(token, request.password());
        recordPasswordResetCompletedAudit(userId, auditContext);
        return ResponseEntity.noContent().build();
    }

    private void recordPasswordResetRequestedAudit(String email, RequestAuditContext context) {
        try {
            var user = userQueryService.findByEmail(email).orElse(null);
            if (user == null) {
                log.info("Password reset requested for unknown email; skipping audit");
                return;
            }
            var metadata = new HashMap<String, Object>();
            metadata.put("email", email);
            metadata.put("source", "self_service");
            auditLogService.record(new AuditEntry(
                    AuditAction.USER_PASSWORD_RESET_REQUESTED,
                    AuditResourceType.USER,
                    user.id(),
                    user.organizationId(),
                    null,
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for USER_PASSWORD_RESET_REQUESTED on email {}", email, ex);
        }
    }

    private void recordPasswordResetCompletedAudit(UUID userId, RequestAuditContext context) {
        try {
            var user = userQueryService.findById(userId).orElse(null);
            if (user == null) {
                log.info("Password reset completed for unknown user {}; skipping audit", userId);
                return;
            }
            var metadata = new HashMap<String, Object>();
            metadata.put("source", "self_service");
            auditLogService.record(new AuditEntry(
                    AuditAction.USER_PASSWORD_RESET_COMPLETED,
                    AuditResourceType.USER,
                    user.id(),
                    user.organizationId(),
                    user.id(),
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for USER_PASSWORD_RESET_COMPLETED on user {}", userId, ex);
        }
    }

    private void recordInvitationAcceptedAudit(UUID userId, UUID organizationId,
                                               RequestAuditContext context) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("source", "invitation");
            auditLogService.record(new AuditEntry(
                    AuditAction.USER_INVITATION_ACCEPTED,
                    AuditResourceType.USER,
                    userId,
                    organizationId,
                    userId,
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for USER_INVITATION_ACCEPTED on user {}", userId, ex);
        }
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email and password, returns JWT access token")
    @ApiResponse(responseCode = "200", description = "Login successful")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                        RequestAuditContext auditContext,
                                        HttpServletResponse response) {
        try {
            var result = authenticationService.login(
                    new LoginCommand(request.email(), request.password(), request.totpCode()));
            setRefreshCookie(response, result.refreshToken(), REFRESH_COOKIE_MAX_AGE);
            recordLoginAudit(AuditAction.USER_LOGIN, request.email(), result.user().id(),
                    result.user().organizationId(), auditContext);
            return ResponseEntity.ok(toLoginResponse(result));
        } catch (TotpRequiredException ex) {
            throw ex;
        } catch (TotpAuthenticationException ex) {
            recordLoginFailureAudit(AuditAction.USER_LOGIN_TOTP_FAILED, request.email(), auditContext,
                    ex.getMessage());
            throw ex;
        } catch (AuthenticationException ex) {
            recordLoginFailureAudit(AuditAction.USER_LOGIN_FAILED, request.email(), auditContext,
                    ex.getMessage());
            throw ex;
        }
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange refresh token cookie for a new access token")
    @ApiResponse(responseCode = "200", description = "Token refreshed successfully")
    @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
    ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var result = authenticationService.refresh(refreshToken);
        setRefreshCookie(response, result.refreshToken(), REFRESH_COOKIE_MAX_AGE);
        return ResponseEntity.ok(toLoginResponse(result));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the refresh token and clear the cookie")
    @ApiResponse(responseCode = "204", description = "Logged out successfully")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        authenticationService.logout(refreshToken);
        setRefreshCookie(response, "", 0);
        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String value, int maxAge) {
        refreshCookieWriter.write(response, value, maxAge);
    }

    private LoginResponse toLoginResponse(com.bablsoft.accessflow.security.api.AuthResult result) {
        var user = result.user();
        var summary = new UserSummary(user.id(), user.email(), user.displayName(),
                user.role().name(), user.authProvider().name(), user.totpEnabled(),
                user.preferredLanguage());
        return new LoginResponse(result.accessToken(), result.tokenType(), result.expiresIn(), summary);
    }

    private void recordLoginAudit(AuditAction action, String email, UUID userId,
                                  UUID organizationId, RequestAuditContext context) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("email", email);
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.USER,
                    userId,
                    organizationId,
                    userId,
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on user {}", action, email, ex);
        }
    }

    private void recordSetupAudit(UUID userId, UUID organizationId, String email,
                                  RequestAuditContext context) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("email", email);
            metadata.put("role", "ADMIN");
            metadata.put("source", "setup");
            auditLogService.record(new AuditEntry(
                    AuditAction.USER_CREATED,
                    AuditResourceType.USER,
                    userId,
                    organizationId,
                    userId,
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for USER_CREATED on setup user {}", userId, ex);
        }
    }

    private void recordLoginFailureAudit(AuditAction action, String email,
                                         RequestAuditContext context, String reason) {
        try {
            var user = userQueryService.findByEmail(email).orElse(null);
            if (user == null) {
                // Without a user we have no organization to scope the row to. Skipping is the
                // pragmatic choice — multi-tenant attribution requires the email match a row.
                log.info("{} for unknown email; skipping audit", action);
                return;
            }
            var metadata = new HashMap<String, Object>();
            metadata.put("email", email);
            if (reason != null) {
                metadata.put("reason", reason);
            }
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.USER,
                    user.id(),
                    user.organizationId(),
                    null,
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on email {}", action, email, ex);
        }
    }
}
