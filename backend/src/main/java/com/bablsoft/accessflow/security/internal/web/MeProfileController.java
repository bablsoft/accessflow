package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserProfileService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.ChangePasswordRequest;
import com.bablsoft.accessflow.security.internal.web.model.ConfirmTotpRequest;
import com.bablsoft.accessflow.security.internal.web.model.DisableTotpRequest;
import com.bablsoft.accessflow.security.internal.web.model.MeProfileResponse;
import com.bablsoft.accessflow.security.internal.web.model.TotpConfirmationResponse;
import com.bablsoft.accessflow.security.internal.web.model.TotpEnrollmentResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateMeProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me Profile", description = "Self-service profile, password, and TOTP endpoints")
@RequiredArgsConstructor
@Slf4j
class MeProfileController {

    private final UserProfileService userProfileService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Get the caller's profile")
    @ApiResponse(responseCode = "200", description = "Profile returned")
    MeProfileResponse getProfile(Authentication authentication) {
        var caller = currentClaims(authentication);
        return MeProfileResponse.from(userProfileService.getProfile(caller.userId()));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update the caller's display name")
    @ApiResponse(responseCode = "200", description = "Profile updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    MeProfileResponse updateProfile(@Valid @RequestBody UpdateMeProfileRequest request,
                                    Authentication authentication,
                                    RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var updated = userProfileService.updateDisplayName(caller.userId(), request.displayName());
        recordAudit(AuditAction.USER_PROFILE_UPDATED, caller, auditContext,
                Map.of("display_name", updated.displayName()));
        return MeProfileResponse.from(updated);
    }

    @PostMapping("/password")
    @Operation(summary = "Change the caller's password (LOCAL accounts only)")
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "422", description = "Current password incorrect or change not allowed")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        userProfileService.changePassword(caller.userId(), request.currentPassword(),
                request.newPassword());
        recordAudit(AuditAction.USER_PASSWORD_CHANGED, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/totp/enroll")
    @Operation(summary = "Begin TOTP enrolment — returns secret + QR code")
    @ApiResponse(responseCode = "200", description = "Enrolment data returned")
    @ApiResponse(responseCode = "422", description = "TOTP already enabled or not allowed for this account")
    TotpEnrollmentResponse enrollTotp(Authentication authentication) {
        var caller = currentClaims(authentication);
        return TotpEnrollmentResponse.from(userProfileService.startTotpEnrollment(caller.userId()));
    }

    @PostMapping("/totp/confirm")
    @Operation(summary = "Confirm TOTP enrolment with a 6-digit code — returns single-use backup codes")
    @ApiResponse(responseCode = "200", description = "TOTP enabled — backup codes returned")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "422", description = "Invalid code or enrolment not started")
    TotpConfirmationResponse confirmTotp(@Valid @RequestBody ConfirmTotpRequest request,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var result = userProfileService.confirmTotpEnrollment(caller.userId(), request.code());
        recordAudit(AuditAction.USER_TOTP_ENABLED, caller, auditContext, Map.of());
        return TotpConfirmationResponse.from(result);
    }

    @PostMapping("/totp/disable")
    @Operation(summary = "Disable TOTP after confirming the caller's password")
    @ApiResponse(responseCode = "204", description = "TOTP disabled")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "422", description = "TOTP not enabled or password incorrect")
    ResponseEntity<Void> disableTotp(@Valid @RequestBody DisableTotpRequest request,
                                     Authentication authentication,
                                     RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        userProfileService.disableTotp(caller.userId(), request.currentPassword());
        recordAudit(AuditAction.USER_TOTP_DISABLED, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            UUID userId = caller.userId();
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.USER,
                    userId,
                    caller.organizationId(),
                    userId,
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on user {}", action, caller.userId(), ex);
        }
    }
}
