package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.ExternalLocalAccountConflictException;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.UserProvisioningService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.OAuth2ConfigView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock UserProvisioningService userProvisioningService;
    @Mock OAuth2ConfigService oauth2ConfigService;
    @Mock OrganizationLookupService organizationLookupService;
    @Mock OAuth2ExchangeCodeStore exchangeCodeStore;
    @Mock OAuth2AuthorizedClientService authorizedClientService;
    @Mock OAuth2EmailResolver emailResolver;
    @Mock AuditLogService auditLogService;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock OAuth2AccessToken accessToken;

    private OAuth2LoginSuccessHandler handler;
    private OAuth2RedirectProperties properties;
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new OAuth2RedirectProperties("https://app.example.com/auth/oauth/callback",
                Duration.ofMinutes(1));
        handler = new OAuth2LoginSuccessHandler(
                userProvisioningService, oauth2ConfigService, organizationLookupService,
                exchangeCodeStore, authorizedClientService, emailResolver, properties, auditLogService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWhenAuthenticationIsNotOAuth2Token() throws Exception {
        handler.onAuthenticationSuccess(request, response,
                new TestingAuthenticationToken("u", "p"));

        verify(response).sendRedirect(contains("error=OAUTH2_UNEXPECTED_AUTH"));
    }

    @Test
    void rejectsMissingEmail() throws Exception {
        stubAuthorizedClient("google");
        var token = oauth2Token("google", Map.of("sub", "1"));
        when(emailResolver.resolve(eq(OAuth2ProviderType.GOOGLE), any(), anyString()))
                .thenReturn(new OAuth2EmailResolver.Resolved(null, null, true));

        handler.onAuthenticationSuccess(request, response, token);

        verify(response).sendRedirect(contains("error=OAUTH2_EMAIL_MISSING"));
    }

    @Test
    void rejectsUnverifiedEmail() throws Exception {
        stubAuthorizedClient("google");
        var token = oauth2Token("google", Map.of("sub", "1", "email", "u@x.com"));
        when(emailResolver.resolve(eq(OAuth2ProviderType.GOOGLE), any(), anyString()))
                .thenReturn(new OAuth2EmailResolver.Resolved("u@x.com", "U", false));

        handler.onAuthenticationSuccess(request, response, token);

        verify(response).sendRedirect(contains("error=OAUTH2_EMAIL_UNVERIFIED"));
    }

    @Test
    void successPathProvisionsAndRedirectsWithCode() throws Exception {
        stubAuthorizedClient("google");
        var token = oauth2Token("google", Map.of("sub", "1", "email", "u@x.com"));
        when(emailResolver.resolve(eq(OAuth2ProviderType.GOOGLE), any(), anyString()))
                .thenReturn(new OAuth2EmailResolver.Resolved("u@x.com", "User", true));
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(oauth2ConfigService.getOrDefault(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(view(UserRoleType.REVIEWER));
        when(userProvisioningService.findOrProvision(eq(orgId), eq("u@x.com"), eq("User"),
                eq(AuthProviderType.OAUTH2), eq(UserRoleType.REVIEWER)))
                .thenReturn(provisionedUser());
        when(exchangeCodeStore.issue(userId)).thenReturn("CODE_XYZ");

        handler.onAuthenticationSuccess(request, response, token);

        verify(response).sendRedirect(
                "https://app.example.com/auth/oauth/callback?code=CODE_XYZ");
        verify(auditLogService).record(any());
    }

    @Test
    void localConflictRedirectsWithSpecificError() throws Exception {
        stubAuthorizedClient("github");
        var token = oauth2Token("github", Map.of("id", 1, "email", "a@b.com"));
        when(emailResolver.resolve(eq(OAuth2ProviderType.GITHUB), any(), anyString()))
                .thenReturn(new OAuth2EmailResolver.Resolved("a@b.com", "User", true));
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(oauth2ConfigService.getOrDefault(orgId, OAuth2ProviderType.GITHUB))
                .thenReturn(view(UserRoleType.ANALYST));
        when(userProvisioningService.findOrProvision(any(), any(), any(), any(), any()))
                .thenThrow(new ExternalLocalAccountConflictException("a@b.com"));

        handler.onAuthenticationSuccess(request, response, token);

        verify(response).sendRedirect(contains("error=OAUTH2_LOCAL_EMAIL_CONFLICT"));
        verify(exchangeCodeStore, never()).issue(any());
    }

    private OAuth2AuthenticationToken oauth2Token(String registrationId, Map<String, Object> attrs) {
        var nameAttr = registrationId.equals("github") ? "id" : "sub";
        var user = new DefaultOAuth2User(
                Set.of(new OAuth2UserAuthority(attrs)),
                attrs,
                nameAttr);
        return new OAuth2AuthenticationToken(user, List.of(), registrationId);
    }

    private void stubAuthorizedClient(String registrationId) {
        var registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("c")
                .clientSecret("s")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/cb")
                .authorizationUri("http://idp/auth")
                .tokenUri("http://idp/token")
                .build();
        when(accessToken.getTokenValue()).thenReturn("provider-access-token");
        var authorizedClient = new OAuth2AuthorizedClient(registration, "principal", accessToken);
        when(authorizedClientService.loadAuthorizedClient(eq(registrationId), anyString()))
                .thenReturn(authorizedClient);
    }

    private OAuth2ConfigView view(UserRoleType defaultRole) {
        return new OAuth2ConfigView(UUID.randomUUID(), orgId, OAuth2ProviderType.GOOGLE,
                "client-id", true, null, null, defaultRole, true,
                Instant.now(), Instant.now());
    }

    private UserView provisionedUser() {
        return new UserView(userId, "u@x.com", "User", UserRoleType.REVIEWER, orgId, true,
                AuthProviderType.OAUTH2, null, null, null, false, Instant.now());
    }

    private static String contains(String needle) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.contains(needle));
    }
}
