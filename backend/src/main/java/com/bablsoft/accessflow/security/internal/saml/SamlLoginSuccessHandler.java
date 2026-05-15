package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ExternalLocalAccountConflictException;
import com.bablsoft.accessflow.core.api.InactiveUserException;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.UserProvisioningService;
import com.bablsoft.accessflow.security.api.SamlConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;

/**
 * Runs after Spring Security SAML2 has validated the IdP-signed SAMLResponse. Extracts identity
 * attributes per the org's mapping config, JIT-provisions the user (auth_provider=SAML), mints a
 * one-time exchange code in Redis, and redirects the browser to the frontend callback with that
 * code. The frontend swaps the code for an AccessFlow JWT pair via
 * {@code POST /api/v1/auth/saml/exchange}. Tokens never appear in the redirect URL.
 */
@Component
@RequiredArgsConstructor
public class SamlLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SamlLoginSuccessHandler.class);

    private final UserProvisioningService userProvisioningService;
    private final SamlConfigService samlConfigService;
    private final OrganizationLookupService organizationLookupService;
    private final SamlExchangeCodeStore exchangeCodeStore;
    private final SamlRedirectProperties redirectProperties;
    private final AuditLogService auditLogService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal principal)) {
            redirectWithError(response, "SAML_UNEXPECTED_AUTH");
            return;
        }
        var organizationId = organizationLookupService.singleOrganization();
        var config = samlConfigService.getOrDefault(organizationId);
        var mapped = SamlAttributeMapper.map(principal, config);
        if (mapped.email() == null) {
            log.info("SAML login refused — assertion did not include attribute {}", config.attrEmail());
            redirectWithError(response, "SAML_EMAIL_MISSING");
            return;
        }
        try {
            var user = userProvisioningService.findOrProvision(
                    organizationId,
                    mapped.email(),
                    mapped.displayName(),
                    AuthProviderType.SAML,
                    mapped.role());
            var code = exchangeCodeStore.issue(user.id());
            recordAudit(user.id(), organizationId, principal, request);
            SecurityContextHolder.clearContext();
            redirectWithCode(response, code);
        } catch (ExternalLocalAccountConflictException ex) {
            log.info("SAML login refused — email {} bound to LOCAL account", ex.email());
            redirectWithError(response, "SAML_LOCAL_EMAIL_CONFLICT");
        } catch (InactiveUserException ex) {
            log.info("SAML login refused — inactive user for email {}", ex.email());
            redirectWithError(response, "ACCOUNT_DISABLED");
        }
    }

    private void redirectWithCode(HttpServletResponse response, String code) throws IOException {
        var uri = UriComponentsBuilder.fromUriString(redirectProperties.frontendCallbackUrl())
                .queryParam("code", code)
                .build(true)
                .toUriString();
        response.sendRedirect(uri);
    }

    private void redirectWithError(HttpServletResponse response, String error) throws IOException {
        var encoded = URLEncoder.encode(error, StandardCharsets.UTF_8);
        var uri = UriComponentsBuilder.fromUriString(redirectProperties.frontendCallbackUrl())
                .queryParam("error", encoded)
                .build(true)
                .toUriString();
        response.sendRedirect(uri);
    }

    private void recordAudit(UUID userId, UUID organizationId,
                             Saml2AuthenticatedPrincipal principal,
                             HttpServletRequest request) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("provider", "SAML");
            if (principal.getRelyingPartyRegistrationId() != null) {
                metadata.put("registration_id", principal.getRelyingPartyRegistrationId());
            }
            auditLogService.record(new AuditEntry(
                    AuditAction.USER_LOGIN,
                    AuditResourceType.USER,
                    userId,
                    organizationId,
                    userId,
                    metadata,
                    request.getRemoteAddr(),
                    request.getHeader("User-Agent")));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for SAML login user {}", userId, ex);
        }
    }
}
