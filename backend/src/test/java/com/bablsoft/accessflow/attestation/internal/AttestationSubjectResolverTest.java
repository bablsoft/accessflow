package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountLookupService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationSubjectResolverTest {

    @Mock UserQueryService userQueryService;
    @Mock ServiceAccountLookupService serviceAccountLookupService;
    @InjectMocks AttestationSubjectResolver resolver;

    private final UUID orgId = UUID.randomUUID();
    private final UUID humanId = UUID.randomUUID();
    private final UUID botId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void emptyPageTouchesNothing() {
        assertThat(resolver.enrich(orgId, List.of())).isEmpty();
        verifyNoInteractions(userQueryService, serviceAccountLookupService);
    }

    @Test
    void humanOnlyPageNeverConsultsServiceAccounts() {
        when(userQueryService.findByIds(Set.of(humanId)))
                .thenReturn(List.of(user(humanId, "alice@example.com", "Alice", PrincipalType.HUMAN)));

        var out = resolver.enrich(orgId, List.of(item(humanId)));

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.subjectPrincipalType()).isEqualTo(PrincipalType.HUMAN);
            assertThat(v.subjectOwnerEmail()).isNull();
        });
        verifyNoInteractions(serviceAccountLookupService);
    }

    @Test
    void serviceAccountSubjectCarriesItsOwner() {
        when(userQueryService.findByIds(Set.of(botId, humanId))).thenReturn(List.of(
                user(botId, "bot@example.com", "Bot", PrincipalType.SERVICE_ACCOUNT),
                user(humanId, "alice@example.com", "Alice", PrincipalType.HUMAN)));
        when(serviceAccountLookupService.listByOrganization(orgId))
                .thenReturn(List.of(account(botId, ownerId), account(UUID.randomUUID(), null)));
        when(userQueryService.findByIds(Set.of(ownerId)))
                .thenReturn(List.of(user(ownerId, "owner@example.com", "Owner", PrincipalType.HUMAN)));

        var out = resolver.enrich(orgId, List.of(item(botId), item(humanId)));

        assertThat(out.get(0).subjectPrincipalType()).isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(out.get(0).subjectOwnerEmail()).isEqualTo("owner@example.com");
        assertThat(out.get(0).subjectOwnerDisplayName()).isEqualTo("Owner");
        assertThat(out.get(1).subjectPrincipalType()).isEqualTo(PrincipalType.HUMAN);
        assertThat(out.get(1).subjectOwnerEmail()).isNull();
    }

    @Test
    void serviceAccountWithoutOwnerSkipsTheOwnerLookup() {
        when(userQueryService.findByIds(Set.of(botId)))
                .thenReturn(List.of(user(botId, "bot@example.com", "Bot", PrincipalType.SERVICE_ACCOUNT)));
        when(serviceAccountLookupService.listByOrganization(orgId)).thenReturn(List.of(account(botId, null)));

        var out = resolver.enrich(orgId, List.of(item(botId)));

        assertThat(out.get(0).subjectPrincipalType()).isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(out.get(0).subjectOwnerEmail()).isNull();
        verify(userQueryService, never()).findByIds(argThat((Collection<UUID> ids) -> ids.isEmpty()));
    }

    @Test
    void deletedSubjectStaysUnresolved() {
        when(userQueryService.findByIds(any())).thenReturn(List.of());

        var out = resolver.enrich(orgId, List.of(item(humanId)));

        assertThat(out.get(0).subjectPrincipalType()).isNull();
        assertThat(out.get(0).subjectOwnerEmail()).isNull();
        verifyNoInteractions(serviceAccountLookupService);
    }

    private AttestationItemView item(UUID subject) {
        return new AttestationItemView(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                UUID.randomUUID(), "Production", subject, "x@example.com", "X", true, false, false, false,
                null, null, null, null, null, null, null, AttestationItemDecision.PENDING, null, null, null,
                null, Instant.parse("2026-07-01T00:00:00Z"));
    }

    private UserView user(UUID id, String email, String name, PrincipalType type) {
        return new UserView(id, email, name, UserRoleType.ANALYST, null, "ANALYST", orgId, true,
                AuthProviderType.LOCAL, null, null, null, false, false, Instant.EPOCH, null, Instant.EPOCH, type);
    }

    private ServiceAccountView account(UUID userId, UUID owner) {
        return new ServiceAccountView(userId, orgId, null, owner, ServiceAccountSource.UI, null, null, null,
                Instant.EPOCH, Instant.EPOCH);
    }
}
