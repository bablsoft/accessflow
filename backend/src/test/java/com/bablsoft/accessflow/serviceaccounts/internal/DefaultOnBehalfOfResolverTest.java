package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountDelegatedPrincipalEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountDelegatedPrincipalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOnBehalfOfResolverTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    @Mock UserQueryService userQueryService;
    @Mock ServiceAccountDelegatedPrincipalRepository repository;

    private DefaultOnBehalfOfResolver resolver;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new DefaultOnBehalfOfResolver(userQueryService, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void blankReferenceIsEmptyWithoutLookups() {
        assertThat(resolver.resolve(AGENT, ORG, "  ")).isEmpty();
        assertThat(resolver.resolve(AGENT, ORG, null)).isEmpty();
        verifyNoInteractions(userQueryService, repository);
    }

    @Test
    void uuidReferenceResolvesByIdAndRequiresALiveGrant() {
        when(userQueryService.findById(alice)).thenReturn(Optional.of(user(alice, ORG, true, PrincipalType.HUMAN)));
        when(repository.findLive(AGENT, alice, NOW)).thenReturn(Optional.of(new ServiceAccountDelegatedPrincipalEntity()));

        assertThat(resolver.resolve(AGENT, ORG, " " + alice + " ")).contains(alice);
    }

    @Test
    void emailReferenceResolvesByExactEmail() {
        when(userQueryService.findByEmail("Alice@Example.com"))
                .thenReturn(Optional.of(user(alice, ORG, true, PrincipalType.HUMAN)));
        when(repository.findLive(AGENT, alice, NOW)).thenReturn(Optional.of(new ServiceAccountDelegatedPrincipalEntity()));

        assertThat(resolver.resolve(AGENT, ORG, "Alice@Example.com")).contains(alice);
        verify(userQueryService).findByEmail(eq("Alice@Example.com"));
    }

    @Test
    void unknownUserIsEmpty() {
        when(userQueryService.findByEmail(any())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(AGENT, ORG, "ghost@example.com")).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void userInAnotherOrganizationIsEmpty() {
        when(userQueryService.findById(alice))
                .thenReturn(Optional.of(user(alice, UUID.randomUUID(), true, PrincipalType.HUMAN)));

        assertThat(resolver.resolve(AGENT, ORG, alice.toString())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void inactiveUserIsEmpty() {
        when(userQueryService.findById(alice)).thenReturn(Optional.of(user(alice, ORG, false, PrincipalType.HUMAN)));

        assertThat(resolver.resolve(AGENT, ORG, alice.toString())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void serviceAccountPrincipalIsEmpty() {
        when(userQueryService.findById(alice))
                .thenReturn(Optional.of(user(alice, ORG, true, PrincipalType.SERVICE_ACCOUNT)));

        assertThat(resolver.resolve(AGENT, ORG, alice.toString())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void humanWithoutALiveGrantIsEmpty() {
        when(userQueryService.findById(alice)).thenReturn(Optional.of(user(alice, ORG, true, PrincipalType.HUMAN)));
        when(repository.findLive(AGENT, alice, NOW)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(AGENT, ORG, alice.toString())).isEmpty();
    }

    private static UserView user(UUID id, UUID organizationId, boolean active, PrincipalType principalType) {
        return new UserView(id, id + "@example.com", "Alice", UserRoleType.ANALYST, UUID.randomUUID(), "ANALYST",
                organizationId, active, AuthProviderType.LOCAL, "hash", null, "en", false, false, NOW, null, NOW,
                principalType);
    }
}
