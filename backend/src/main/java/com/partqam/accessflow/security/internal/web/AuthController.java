package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditResourceType;
import com.partqam.accessflow.audit.api.RequestAuditContext;
import com.partqam.accessflow.core.api.BootstrapService;
import com.partqam.accessflow.core.api.SetupCommand;
import com.partqam.accessflow.core.api.UserQueryService;
import com.partqam.accessflow.security.api.AuthenticationService;
import com.partqam.accessflow.security.api.LoginCommand;
import com.partqam.accessflow.security.internal.web.model.LoginRequest;
import com.partqam.accessflow.security.internal.web.model.LoginResponse;
import com.partqam.accessflow.security.internal.web.model.SetupRequest;
import com.partqam.accessflow.security.internal.web.model.SetupStatusResponse;
import com.partqam.accessflow.security.internal.web.model.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final int REFRESH_COOKIE_MAX_AGE = 7 * 24 * 3600;

    private final AuthenticationService authenticationService;
    private final AuditLogService auditLogService;
    private final UserQueryService userQueryService;
    private final BootstrapService bootstrapService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/setup-status")
    @Operation(summary = "Report whether the deployment still needs first-time admin setup")
    @ApiResponse(responseCode = "200", description = "Setup status returned")
    @SecurityRequirements
    SetupStatusResponse setupStatus() {
        return new SetupStatusResponse(bootstrapService.isSetupRequired());
    }

    @PostMapping("/setup")
    @Operation(summary = "Create the first organization and admin user (first-run only)")
    @ApiResponse(responseCode = "201", description = "Setup completed")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Setup already completed or email already exists")
    @SecurityRequirements
    ResponseEntity<Void> setup(@Valid @RequestBody SetupRequest request,
                               HttpServletRequest httpRequest) {
        var passwordHash = passwordEncoder.encode(request.password());
        var result = bootstrapService.performSetup(new SetupCommand(
                request.organizationName(),
                request.email(),
                request.displayName(),
                passwordHash));
        recordSetupAudit(result.userId(), result.organizationId(), request.email(),
                RequestAuditContext.from(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email and password, returns JWT access token")
    @ApiResponse(responseCode = "200", description = "Login successful")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest,
                                        HttpServletResponse response) {
        var context = RequestAuditContext.from(httpRequest);
        try {
            var result = authenticationService.login(new LoginCommand(request.email(), request.password()));
            setRefreshCookie(response, result.refreshToken(), REFRESH_COOKIE_MAX_AGE);
            recordLoginAudit(AuditAction.USER_LOGIN, request.email(), result.user().id(),
                    result.user().organizationId(), context);
            return ResponseEntity.ok(toLoginResponse(result));
        } catch (AuthenticationException ex) {
            recordLoginFailureAudit(request.email(), context, ex.getMessage());
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
        var cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(maxAge)
                .path("/api/v1/auth")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private LoginResponse toLoginResponse(com.partqam.accessflow.security.api.AuthResult result) {
        var user = result.user();
        var summary = new UserSummary(user.id(), user.email(), user.displayName(),
                user.role().name());
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

    private void recordLoginFailureAudit(String email, RequestAuditContext context, String reason) {
        try {
            var user = userQueryService.findByEmail(email).orElse(null);
            if (user == null) {
                // Without a user we have no organization to scope the row to. Skipping is the
                // pragmatic choice — multi-tenant attribution requires the email match a row.
                log.info("USER_LOGIN_FAILED for unknown email; skipping audit");
                return;
            }
            var metadata = new HashMap<String, Object>();
            metadata.put("email", email);
            if (reason != null) {
                metadata.put("reason", reason);
            }
            auditLogService.record(new AuditEntry(
                    AuditAction.USER_LOGIN_FAILED,
                    AuditResourceType.USER,
                    user.id(),
                    user.organizationId(),
                    null,
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for USER_LOGIN_FAILED on email {}", email, ex);
        }
    }
}
