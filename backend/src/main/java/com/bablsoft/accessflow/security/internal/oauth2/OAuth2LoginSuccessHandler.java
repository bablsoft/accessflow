package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ExternalLocalAccountConflictException;
import com.bablsoft.accessflow.core.api.InactiveUserException;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.UserProvisioningService;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;

/**
 * Runs after a provider callback completes. Extracts identity from the userinfo claims, performs
 * JIT user provisioning, mints a one-time exchange code in Redis, and redirects the browser to
 * the frontend callback URL with that code. The frontend swaps the code for an AccessFlow JWT
 * pair via {@code POST /api/v1/auth/oauth2/exchange}.
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserProvisioningService userProvisioningService;
    private final OAuth2ConfigService oauth2ConfigService;
    private final OrganizationLookupService organizationLookupService;
    private final OAuth2ExchangeCodeStore exchangeCodeStore;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final OAuth2EmailResolver emailResolver;
    private final OAuth2RedirectProperties properties;
    private final AuditLogService auditLogService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            redirectWithError(response, "OAUTH2_UNEXPECTED_AUTH");
            return;
        }

        var registrationId = token.getAuthorizedClientRegistrationId();
        OAuth2ProviderType provider;
        try {
            provider = OAuth2ProviderType.valueOf(registrationId.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            redirectWithError(response, "OAUTH2_UNKNOWN_PROVIDER");
            return;
        }

        var accessToken = resolveAccessToken(token);
        var attributes = token.getPrincipal().getAttributes();
        var resolved = emailResolver.resolve(provider, attributes, accessToken);

        if (resolved.email() == null || resolved.email().isBlank()) {
            redirectWithError(response, "OAUTH2_EMAIL_MISSING");
            return;
        }
        if (Boolean.FALSE.equals(resolved.emailVerified())) {
            redirectWithError(response, "OAUTH2_EMAIL_UNVERIFIED");
            return;
        }

        var organizationId = organizationLookupService.singleOrganization();
        var config = oauth2ConfigService.getOrDefault(organizationId, provider);

        try {
            var user = userProvisioningService.findOrProvision(
                    organizationId,
                    resolved.email(),
                    resolved.displayName(),
                    AuthProviderType.OAUTH2,
                    config.defaultRole());
            var code = exchangeCodeStore.issue(user.id());
            recordAudit(user.id(), organizationId, provider, request);
            SecurityContextHolder.clearContext();
            redirectWithCode(response, code);
        } catch (ExternalLocalAccountConflictException ex) {
            log.info("OAuth2 login refused — email {} bound to LOCAL account", ex.email());
            redirectWithError(response, "OAUTH2_LOCAL_EMAIL_CONFLICT");
        } catch (InactiveUserException ex) {
            log.info("OAuth2 login refused — inactive user for email {}", ex.email());
            redirectWithError(response, "ACCOUNT_DISABLED");
        }
    }

    private String resolveAccessToken(OAuth2AuthenticationToken token) {
        var client = authorizedClientService.loadAuthorizedClient(
                token.getAuthorizedClientRegistrationId(), token.getName());
        return client != null && client.getAccessToken() != null
                ? client.getAccessToken().getTokenValue()
                : null;
    }

    private void redirectWithCode(HttpServletResponse response, String code) throws IOException {
        var uri = UriComponentsBuilder.fromUriString(properties.frontendCallbackUrl())
                .queryParam("code", code)
                .build(true)
                .toUriString();
        response.sendRedirect(uri);
    }

    private void redirectWithError(HttpServletResponse response, String error) throws IOException {
        var encoded = URLEncoder.encode(error, StandardCharsets.UTF_8);
        var uri = UriComponentsBuilder.fromUriString(properties.frontendCallbackUrl())
                .queryParam("error", encoded)
                .build(true)
                .toUriString();
        response.sendRedirect(uri);
    }

    private void recordAudit(java.util.UUID userId, java.util.UUID organizationId,
                             OAuth2ProviderType provider, HttpServletRequest request) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("provider", provider.name());
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
            log.error("Audit write failed for OAuth2 login user {}", userId, ex);
        }
    }
}
