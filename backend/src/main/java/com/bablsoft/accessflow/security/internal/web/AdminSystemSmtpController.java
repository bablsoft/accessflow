package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.SaveSystemSmtpCommand;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.SystemSmtpView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.SystemSmtpResponse;
import com.bablsoft.accessflow.security.internal.web.model.TestSystemSmtpRequest;
import com.bablsoft.accessflow.security.internal.web.model.UpdateSystemSmtpRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/admin/system-smtp")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin System SMTP", description = "Per-organization global SMTP configuration (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminSystemSmtpController {

    private final SystemSmtpService systemSmtpService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Read the current system SMTP configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Current configuration with masked password")
    @ApiResponse(responseCode = "404", description = "No configuration set")
    ResponseEntity<SystemSmtpResponse> get(Authentication authentication) {
        var caller = claims(authentication);
        var view = systemSmtpService.findForOrganization(caller.organizationId()).orElse(null);
        if (view == null) {
            return ResponseEntity.notFound().build();
        }
        var passwordSet = systemSmtpService.resolveSendingConfig(caller.organizationId())
                .map(c -> c.plaintextPassword() != null && !c.plaintextPassword().isBlank())
                .orElse(false);
        return ResponseEntity.ok(SystemSmtpResponse.from(view, passwordSet));
    }

    @PutMapping
    @Operation(summary = "Create or update the system SMTP configuration")
    @ApiResponse(responseCode = "200", description = "Configuration saved")
    @ApiResponse(responseCode = "400", description = "Validation error")
    SystemSmtpResponse upsert(@Valid @RequestBody UpdateSystemSmtpRequest request,
                              Authentication authentication,
                              RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var plaintextPassword = isMaskedOrBlank(request.smtpPassword()) ? null : request.smtpPassword();
        var command = new SaveSystemSmtpCommand(
                request.host(),
                request.port(),
                request.username(),
                plaintextPassword,
                request.tls() == null ? Boolean.TRUE : request.tls(),
                request.fromAddress(),
                request.fromName());
        SystemSmtpView saved = systemSmtpService.saveOrUpdate(caller.organizationId(), command);
        recordAudit(AuditAction.SYSTEM_SMTP_UPDATED, caller, auditContext,
                Map.of("host", saved.host(), "from_address", saved.fromAddress()));
        var passwordSet = systemSmtpService.resolveSendingConfig(caller.organizationId())
                .map(c -> c.plaintextPassword() != null && !c.plaintextPassword().isBlank())
                .orElse(false);
        return SystemSmtpResponse.from(saved, passwordSet);
    }

    @DeleteMapping
    @Operation(summary = "Remove the system SMTP configuration")
    @ApiResponse(responseCode = "204", description = "Configuration removed")
    ResponseEntity<Void> delete(Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        systemSmtpService.delete(caller.organizationId());
        recordAudit(AuditAction.SYSTEM_SMTP_DELETED, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @Operation(summary = "Send a synthetic test email through the system SMTP")
    @ApiResponse(responseCode = "200", description = "Test email accepted by the SMTP server")
    @ApiResponse(responseCode = "422", description = "SMTP is not configured and no override was provided")
    ResponseEntity<Void> test(@Valid @RequestBody(required = false) TestSystemSmtpRequest request,
                              Authentication authentication,
                              RequestAuditContext auditContext) {
        var caller = claims(authentication);
        SaveSystemSmtpCommand override = null;
        if (hasOverride(request)) {
            var plaintextPassword = isMaskedOrBlank(request.smtpPassword()) ? null : request.smtpPassword();
            override = new SaveSystemSmtpCommand(
                    request.host(),
                    request.port() == null ? 0 : request.port(),
                    request.username(),
                    plaintextPassword,
                    request.tls() == null ? Boolean.TRUE : request.tls(),
                    request.fromAddress(),
                    request.fromName());
        }
        var to = request == null ? null : request.to();
        try {
            systemSmtpService.sendTest(caller.organizationId(), override, to);
        } catch (SystemSmtpNotConfiguredException ex) {
            throw ex;
        }
        recordAudit(AuditAction.SYSTEM_SMTP_TEST_SENT, caller, auditContext,
                Map.of("to", to == null ? "" : to));
        return ResponseEntity.ok().build();
    }

    private static boolean hasOverride(TestSystemSmtpRequest request) {
        if (request == null) {
            return false;
        }
        return request.host() != null && !request.host().isBlank()
                && request.port() != null
                && request.fromAddress() != null && !request.fromAddress().isBlank();
    }

    private static boolean isMaskedOrBlank(String value) {
        if (value == null) {
            return true;
        }
        var trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return SystemSmtpResponse.MASKED_PASSWORD.equals(trimmed);
    }

    private JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, JwtClaims caller, RequestAuditContext auditContext,
                             Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.SYSTEM_SMTP,
                    caller.organizationId(),
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} (org {})", action, caller.organizationId(), ex);
        }
    }
}
