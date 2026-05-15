package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ExternalLocalAccountConflictException;
import com.bablsoft.accessflow.core.api.InactiveUserException;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.UserProvisioningService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.SamlConfigService;
import com.bablsoft.accessflow.security.api.SamlConfigView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginSuccessHandlerTest {

    @Mock UserProvisioningService userProvisioningService;
    @Mock SamlConfigService samlConfigService;
    @Mock OrganizationLookupService organizationLookupService;
    @Mock SamlExchangeCodeStore exchangeCodeStore;
    @Mock AuditLogService auditLogService;

    private SamlLoginSuccessHandler handler;
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var props = new SamlRedirectProperties("http://frontend/auth/saml/callback", Duration.ofMinutes(1));
        handler = new SamlLoginSuccessHandler(userProvisioningService, samlConfigService,
                organizationLookupService, exchangeCodeStore, props, auditLogService);
    }

    @Test
    void happyPathProvisionsUserIssuesCodeAndRedirectsWithCode() throws Exception {
        var principal = new DefaultSaml2AuthenticatedPrincipal("alice", Map.of(
                "email", List.<Object>of("alice@example.com"),
                "displayName", List.<Object>of("Alice")));
        var auth = new TestingAuthenticationToken(principal, "n/a");
        var request = mockRequest();
        var response = org.mockito.Mockito.mock(HttpServletResponse.class);

        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigService.getOrDefault(orgId)).thenReturn(activeConfig());
        when(userProvisioningService.findOrProvision(eq(orgId), eq("alice@example.com"), eq("Alice"),
                eq(AuthProviderType.SAML), eq(UserRoleType.ANALYST)))
                .thenReturn(view(userId, "alice@example.com"));
        when(exchangeCodeStore.issue(userId)).thenReturn("CODE_XYZ");

        handler.onAuthenticationSuccess(request, response, auth);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        assertThat(captor.getValue()).startsWith("http://frontend/auth/saml/callback?code=CODE_XYZ");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void redirectsWithErrorWhenPrincipalIsNotSamlPrincipal() throws Exception {
        var auth = new TestingAuthenticationToken("not-a-saml-principal", "n/a");
        var request = mockRequest();
        var response = org.mockito.Mockito.mock(HttpServletResponse.class);

        handler.onAuthenticationSuccess(request, response, auth);

        assertThat(lastRedirect(response)).contains("error=SAML_UNEXPECTED_AUTH");
        verify(exchangeCodeStore, never()).issue(any());
    }

    @Test
    void redirectsWithErrorWhenEmailMissing() throws Exception {
        var principal = new DefaultSaml2AuthenticatedPrincipal("opaque", Map.of(
                "displayName", List.<Object>of("Anon")));
        var auth = new TestingAuthenticationToken(principal, "n/a");
        var request = mockRequest();
        var response = org.mockito.Mockito.mock(HttpServletResponse.class);

        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigService.getOrDefault(orgId)).thenReturn(activeConfig());

        handler.onAuthenticationSuccess(request, response, auth);

        assertThat(lastRedirect(response)).contains("error=SAML_EMAIL_MISSING");
        verify(userProvisioningService, never()).findOrProvision(any(), any(), any(), any(), any());
    }

    @Test
    void mapsExternalLocalConflictToLocalEmailConflictError() throws Exception {
        var principal = new DefaultSaml2AuthenticatedPrincipal("user", Map.of(
                "email", List.<Object>of("bob@example.com"),
                "displayName", List.<Object>of("Bob")));
        var auth = new TestingAuthenticationToken(principal, "n/a");
        var request = mockRequest();
        var response = org.mockito.Mockito.mock(HttpServletResponse.class);

        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigService.getOrDefault(orgId)).thenReturn(activeConfig());
        when(userProvisioningService.findOrProvision(any(), any(), any(), any(), any()))
                .thenThrow(new ExternalLocalAccountConflictException("bob@example.com"));

        handler.onAuthenticationSuccess(request, response, auth);

        assertThat(lastRedirect(response)).contains("error=SAML_LOCAL_EMAIL_CONFLICT");
    }

    @Test
    void mapsInactiveUserToAccountDisabledError() throws Exception {
        var principal = new DefaultSaml2AuthenticatedPrincipal("user", Map.of(
                "email", List.<Object>of("carol@example.com"),
                "displayName", List.<Object>of("Carol")));
        var auth = new TestingAuthenticationToken(principal, "n/a");
        var request = mockRequest();
        var response = org.mockito.Mockito.mock(HttpServletResponse.class);

        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigService.getOrDefault(orgId)).thenReturn(activeConfig());
        when(userProvisioningService.findOrProvision(any(), any(), any(), any(), any()))
                .thenThrow(new InactiveUserException("carol@example.com"));

        handler.onAuthenticationSuccess(request, response, auth);

        assertThat(lastRedirect(response)).contains("error=ACCOUNT_DISABLED");
    }

    private HttpServletRequest mockRequest() {
        var request = org.mockito.Mockito.mock(HttpServletRequest.class);
        org.mockito.Mockito.lenient().when(request.getRemoteAddr()).thenReturn("198.51.100.4");
        org.mockito.Mockito.lenient().when(request.getHeader("User-Agent")).thenReturn("AccessFlow-Test/1.0");
        return request;
    }

    private String lastRedirect(HttpServletResponse response) throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        return captor.getValue();
    }

    private SamlConfigView activeConfig() {
        return new SamlConfigView(UUID.randomUUID(), orgId,
                "https://idp.example.com/metadata", "idp-entity", "sp-entity",
                "https://app.example.com/api/v1/auth/saml/acs", null, true,
                "email", "displayName", null,
                UserRoleType.ANALYST, true, Instant.now(), Instant.now());
    }

    private UserView view(UUID id, String email) {
        return new UserView(id, email, "Display", UserRoleType.ANALYST, orgId, true,
                AuthProviderType.SAML, null, null, null, false, Instant.now());
    }
}
