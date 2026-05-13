package com.bablsoft.accessflow.mcp.internal.auth;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserProfileService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.mcp.api.ApiKeyService;
import com.bablsoft.accessflow.security.api.JwtClaims;
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
    @Mock FilterChain chain;

    ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(apiKeyService, userProfileService);
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
        when(apiKeyService.resolveUserId("af_valid")).thenReturn(Optional.of(userId));
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
        when(apiKeyService.resolveUserId("af_xyz")).thenReturn(Optional.of(userId));
        when(userProfileService.getProfile(userId)).thenReturn(activeUser(userId, UUID.randomUUID()));

        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "ApiKey af_xyz");
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void unknown_key_does_not_authenticate_but_still_passes_chain() throws Exception {
        when(apiKeyService.resolveUserId("af_unknown")).thenReturn(Optional.empty());
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_unknown");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void inactive_user_does_not_authenticate() throws Exception {
        var userId = UUID.randomUUID();
        when(apiKeyService.resolveUserId("af_inactive")).thenReturn(Optional.of(userId));
        when(userProfileService.getProfile(userId)).thenReturn(inactiveUser(userId));
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_inactive");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void already_authenticated_skips_lookup() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(
                new JwtClaims(UUID.randomUUID(), "a@b.c", UserRoleType.ADMIN, UUID.randomUUID())));
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_anything");
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        verify(apiKeyService, never()).resolveUserId(any());
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
