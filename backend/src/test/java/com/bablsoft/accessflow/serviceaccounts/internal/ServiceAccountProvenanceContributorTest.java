package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.OnBehalfOfPrincipalService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountLookupService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceAccountProvenanceContributorTest {

    @Mock ServiceAccountLookupService lookupService;
    @Mock OnBehalfOfPrincipalService onBehalfOfPrincipalService;

    private ServiceAccountProvenanceContributor contributor;
    private final UUID userId = UUID.randomUUID();
    private final UUID apiKeyId = UUID.randomUUID();
    private final JwtClaims claims = new JwtClaims(userId, "bot@example.com", UserRoleType.READONLY,
            null, "READONLY", Set.<Permission>of(), UUID.randomUUID(), false);

    @BeforeEach
    void setUp() {
        contributor = new ServiceAccountProvenanceContributor(lookupService, onBehalfOfPrincipalService);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyOffTheRequestThread() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims, apiKeyId));

        assertThat(contributor.contribute()).isEmpty();
        verifyNoInteractions(lookupService, onBehalfOfPrincipalService);
    }

    @Test
    void emptyForAJwtSession() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(claims, null, "ROLE_USER"));

        assertThat(contributor.contribute()).isEmpty();
        verifyNoInteractions(lookupService, onBehalfOfPrincipalService);
    }

    @Test
    void emptyWhenAnonymous() {
        assertThat(contributor.contribute()).isEmpty();
    }

    @Test
    void apiKeyRequestByAServiceAccountWithoutPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims, apiKeyId));
        when(lookupService.findByUserId(userId)).thenReturn(Optional.of(new ServiceAccountView(
                userId, claims.organizationId(), null, null, ServiceAccountSource.UI, List.of(), null, null,
                Instant.now(), Instant.now())));
        when(onBehalfOfPrincipalService.current()).thenReturn(Optional.empty());

        assertThat(contributor.contribute())
                .containsEntry(ServiceAccountProvenanceContributor.API_KEY_ID, apiKeyId.toString())
                .containsEntry(ServiceAccountProvenanceContributor.SERVICE_ACCOUNT, true)
                .doesNotContainKey(ServiceAccountProvenanceContributor.ON_BEHALF_OF_USER_ID);
    }

    @Test
    void apiKeyRequestByAHumanActingForNobody() {
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims, apiKeyId));
        when(lookupService.findByUserId(userId)).thenReturn(Optional.empty());
        when(onBehalfOfPrincipalService.current()).thenReturn(Optional.empty());

        assertThat(contributor.contribute())
                .containsEntry(ServiceAccountProvenanceContributor.SERVICE_ACCOUNT, false);
    }

    @Test
    void onBehalfOfPrincipalIsStamped() {
        var alice = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(claims, apiKeyId));
        when(lookupService.findByUserId(userId)).thenReturn(Optional.empty());
        when(onBehalfOfPrincipalService.current()).thenReturn(Optional.of(alice));

        assertThat(contributor.contribute())
                .containsEntry(ServiceAccountProvenanceContributor.ON_BEHALF_OF_USER_ID, alice.toString());
    }

    private static final class StubApiKeyToken extends AbstractAuthenticationToken implements ApiKeyAuthentication {
        private final JwtClaims principal;
        private final UUID apiKeyId;

        StubApiKeyToken(JwtClaims principal, UUID apiKeyId) {
            super(List.of());
            this.principal = principal;
            this.apiKeyId = apiKeyId;
            setAuthenticated(true);
        }

        @Override
        public UUID apiKeyId() {
            return apiKeyId;
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
