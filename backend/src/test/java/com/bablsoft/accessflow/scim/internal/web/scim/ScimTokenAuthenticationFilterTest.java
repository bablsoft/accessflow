package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.scim.api.ScimConfigService;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.api.ScimTokenService;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScimTokenAuthenticationFilterTest {

    @Mock ScimTokenService tokenService;
    @Mock ScimConfigService configService;
    @Mock OrganizationLookupService organizationLookupService;
    @Mock FilterChain filterChain;

    ScimTokenAuthenticationFilter filter;

    private final UUID orgId = UUID.randomUUID();
    private final ScimPrincipal principal = new ScimPrincipal(orgId, UUID.randomUUID(), "okta");

    @BeforeEach
    void setUp() {
        filter = new ScimTokenAuthenticationFilter(tokenService, configService,
                organizationLookupService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenWithEnabledConfigAuthenticates() throws Exception {
        when(tokenService.authenticate("af_scim_x")).thenReturn(Optional.of(principal));
        when(configService.isEnabled(orgId)).thenReturn(true);
        when(organizationLookupService.isDisabled(orgId)).thenReturn(false);

        filter.doFilterInternal(request("Bearer af_scim_x"), new MockHttpServletResponse(),
                filterChain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isInstanceOf(ScimAuthenticationToken.class);
        assertThat(authentication.getPrincipal()).isEqualTo(principal);
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabledConfigLeavesContextEmpty() throws Exception {
        when(tokenService.authenticate("af_scim_x")).thenReturn(Optional.of(principal));
        when(configService.isEnabled(orgId)).thenReturn(false);

        filter.doFilterInternal(request("Bearer af_scim_x"), new MockHttpServletResponse(),
                filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void disabledOrganizationLeavesContextEmpty() throws Exception {
        when(tokenService.authenticate("af_scim_x")).thenReturn(Optional.of(principal));
        when(configService.isEnabled(orgId)).thenReturn(true);
        when(organizationLookupService.isDisabled(orgId)).thenReturn(true);

        filter.doFilterInternal(request("Bearer af_scim_x"), new MockHttpServletResponse(),
                filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unknownTokenLeavesContextEmpty() throws Exception {
        when(tokenService.authenticate("af_scim_x")).thenReturn(Optional.empty());

        filter.doFilterInternal(request("Bearer af_scim_x"), new MockHttpServletResponse(),
                filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingOrNonBearerHeaderIsIgnored() throws Exception {
        filter.doFilterInternal(request(null), new MockHttpServletResponse(), filterChain);
        filter.doFilterInternal(request("ApiKey af_x"), new MockHttpServletResponse(),
                filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest("GET", "/scim/v2/Users");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
