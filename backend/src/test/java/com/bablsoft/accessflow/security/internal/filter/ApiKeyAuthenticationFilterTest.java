package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.UserProfileService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.api.ResolvedApiKey;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    @Mock ApiKeyService apiKeyService;
    @Mock UserProfileService userProfileService;
    @Mock OrganizationLookupService organizationLookupService;
    @Mock FilterChain chain;

    ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(apiKeyService, userProfileService,
                organizationLookupService,
                (roleId, fallback) -> fallback != null
                        ? SystemRolePermissions.of(fallback) : java.util.Set.of());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void no_header_leaves_security_context_empty() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, res);
    }

    @Test
    void x_api_key_header_populates_principal_with_jwt_claims() throws Exception {
        var userId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        when(apiKeyService.resolve("af_valid")).thenReturn(Optional.of(new ResolvedApiKey(UUID.randomUUID(), userId)));
        when(userProfileService.getProfile(userId)).thenReturn(activeUser(userId, orgId));

        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_valid");
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(JwtClaims.class);
        var claims = (JwtClaims) auth.getPrincipal();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.organizationId()).isEqualTo(orgId);
        assertThat(auth.getAuthorities()).extracting(Object::toString).contains("ROLE_ANALYST");
    }

    @Test
    void authorization_apikey_scheme_also_supported() throws Exception {
        var userId = UUID.randomUUID();
        when(apiKeyService.resolve("af_xyz")).thenReturn(Optional.of(new ResolvedApiKey(UUID.randomUUID(), userId)));
        when(userProfileService.getProfile(userId)).thenReturn(activeUser(userId, UUID.randomUUID()));

        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "ApiKey af_xyz");
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void unknown_key_does_not_authenticate_but_still_passes_chain() throws Exception {
        when(apiKeyService.resolve("af_unknown")).thenReturn(Optional.empty());
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_unknown");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void inactive_user_does_not_authenticate() throws Exception {
        var userId = UUID.randomUUID();
        when(apiKeyService.resolve("af_inactive")).thenReturn(Optional.of(new ResolvedApiKey(UUID.randomUUID(), userId)));
        when(userProfileService.getProfile(userId)).thenReturn(inactiveUser(userId));
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_inactive");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void disabled_organization_does_not_authenticate() throws Exception {
        var userId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        when(apiKeyService.resolve("af_disabled_org")).thenReturn(Optional.of(new ResolvedApiKey(UUID.randomUUID(), userId)));
        when(userProfileService.getProfile(userId)).thenReturn(activeUser(userId, orgId));
        when(organizationLookupService.isDisabled(orgId)).thenReturn(true);
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_disabled_org");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void already_authenticated_skips_lookup() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(
                UUID.randomUUID(),
                JwtClaims.forSystemRole(UUID.randomUUID(), "a@b.c", UserRoleType.ADMIN, UUID.randomUUID())));
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_anything");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        verify(apiKeyService, never()).resolve(any());
    }

    @Test
    void resolved_token_exposes_the_originating_api_key_id() throws Exception {
        var userId = UUID.randomUUID();
        var apiKeyId = UUID.randomUUID();
        when(apiKeyService.resolve("af_valid"))
                .thenReturn(Optional.of(new ResolvedApiKey(apiKeyId, userId, "reporting")));
        when(userProfileService.getProfile(userId)).thenReturn(activeUser(userId, UUID.randomUUID()));

        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_valid");
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(ApiKeyAuthentication.class);
        assertThat(((ApiKeyAuthentication) auth).apiKeyId()).isEqualTo(apiKeyId);
        assertThat(((ApiKeyAuthentication) auth).applicationName()).isEqualTo("reporting");
        // The principal is byte-for-byte the JWT-path shape: the key id rides beside it, not in it.
        assertThat(((JwtClaims) auth.getPrincipal()).userId()).isEqualTo(userId);
    }

    @Test
    void jwt_authenticated_request_is_left_alone_and_is_not_api_key_authentication() throws Exception {
        var jwtToken = new JwtAuthenticationToken(
                JwtClaims.forSystemRole(UUID.randomUUID(), "a@b.c", UserRoleType.ADMIN, UUID.randomUUID()));
        SecurityContextHolder.getContext().setAuthentication(jwtToken);
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_anything");

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isSameAs(jwtToken);
        assertThat(auth).isNotInstanceOf(ApiKeyAuthentication.class);
        verify(apiKeyService, never()).resolve(any());
    }

    private UserView activeUser(UUID userId, UUID orgId) {
        return new UserView(userId, "user@example.com", "User", UserRoleType.ANALYST, orgId,
                true, AuthProviderType.LOCAL, "$2a$10$", null, "en", false, Instant.now());
    }

    private UserView inactiveUser(UUID userId) {
        return new UserView(userId, "user@example.com", "User", UserRoleType.ANALYST, UUID.randomUUID(),
                false, AuthProviderType.LOCAL, "$2a$10$", null, "en", false, Instant.now());
    }
}
