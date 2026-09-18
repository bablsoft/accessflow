package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.serviceaccounts.api.GrantServiceAccountDelegationCommand;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationExistsException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationPrincipalInvalidException;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationStatus;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountNotFoundException;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountDelegatedPrincipalEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountDelegatedPrincipalRepository;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultServiceAccountDelegationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    @Mock ServiceAccountDelegatedPrincipalRepository repository;
    @Mock ServiceAccountRepository serviceAccountRepository;
    @Mock UserAdminService userAdminService;

    private DefaultServiceAccountDelegationService service;
    private final UUID agent = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultServiceAccountDelegationService(repository, serviceAccountRepository,
                userAdminService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(serviceAccountRepository.findByUserIdAndOrganizationId(agent, ORG))
                .thenReturn(Optional.of(new ServiceAccountEntity()));
        when(userAdminService.findByIds(eq(ORG), any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(1);
            return ids.stream().filter(id -> id.equals(alice) || id.equals(agent))
                    .collect(java.util.stream.Collectors.toMap(id -> id,
                            id -> user(id, true, id.equals(agent) ? PrincipalType.SERVICE_ACCOUNT : PrincipalType.HUMAN)));
        });
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void grantPersistsAnOpenEndedRowAndReturnsAnActiveView() {
        var view = service.grant(ORG, admin, new GrantServiceAccountDelegationCommand(agent, alice, null));

        var captor = ArgumentCaptor.forClass(ServiceAccountDelegatedPrincipalEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        var row = captor.getValue();
        assertThat(row.getId()).isNotNull();
        assertThat(row.getOrganizationId()).isEqualTo(ORG);
        assertThat(row.getServiceAccountUserId()).isEqualTo(agent);
        assertThat(row.getPrincipalUserId()).isEqualTo(alice);
        assertThat(row.getGrantedBy()).isEqualTo(admin);
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getExpiresAt()).isNull();
        assertThat(view.status()).isEqualTo(ServiceAccountDelegationStatus.ACTIVE);
        assertThat(view.principalEmail()).isEqualTo(alice + "@example.com");
        assertThat(view.serviceAccountEmail()).isEqualTo(agent + "@example.com");
    }

    @Test
    void grantWithAFutureExpiryKeepsIt() {
        var expiry = NOW.plus(Duration.ofDays(7));

        var view = service.grant(ORG, admin, new GrantServiceAccountDelegationCommand(agent, alice, expiry));

        assertThat(view.expiresAt()).isEqualTo(expiry);
        assertThat(view.status()).isEqualTo(ServiceAccountDelegationStatus.ACTIVE);
    }

    @Test
    void grantRejectsAPastExpiry() {
        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(agent, alice, NOW)))
                .isInstanceOf(ServiceAccountDelegationInvalidException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void grantRejectsAnUnknownOrHumanServiceAccountAs404() {
        var human = UUID.randomUUID();
        when(serviceAccountRepository.findByUserIdAndOrganizationId(human, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(human, alice, null)))
                .isInstanceOf(ServiceAccountNotFoundException.class);
    }

    @Test
    void grantRejectsAnUnknownPrincipal() {
        var ghost = UUID.randomUUID();

        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(agent, ghost, null)))
                .isInstanceOf(ServiceAccountDelegationPrincipalInvalidException.class)
                .extracting("principalUserId").isEqualTo(ghost);
    }

    @Test
    void grantRejectsANullPrincipal() {
        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(agent, null, null)))
                .isInstanceOf(ServiceAccountDelegationPrincipalInvalidException.class);
    }

    @Test
    void grantRejectsAnInactivePrincipal() {
        when(userAdminService.findByIds(eq(ORG), any()))
                .thenReturn(Map.of(alice, user(alice, false, PrincipalType.HUMAN)));

        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(agent, alice, null)))
                .isInstanceOf(ServiceAccountDelegationPrincipalInvalidException.class);
    }

    @Test
    void grantRejectsAServiceAccountAsPrincipal() {
        var otherBot = UUID.randomUUID();
        when(userAdminService.findByIds(eq(ORG), any()))
                .thenReturn(Map.of(otherBot, user(otherBot, true, PrincipalType.SERVICE_ACCOUNT)));

        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(agent, otherBot, null)))
                .isInstanceOf(ServiceAccountDelegationPrincipalInvalidException.class);
    }

    @Test
    void grantRejectsADuplicateLiveRow() {
        when(repository.findByServiceAccountUserIdAndPrincipalUserIdAndRevokedAtIsNull(agent, alice))
                .thenReturn(Optional.of(row(NOW, null, null)));

        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(agent, alice, null)))
                .isInstanceOf(ServiceAccountDelegationExistsException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void grantRetiresAnExpiredUnrevokedRowInsteadOfAnsweringADuplicate() {
        var expired = row(NOW.minusSeconds(3600), NOW.minusSeconds(1), null);
        when(repository.findByServiceAccountUserIdAndPrincipalUserIdAndRevokedAtIsNull(agent, alice))
                .thenReturn(Optional.of(expired));

        var view = service.grant(ORG, admin, new GrantServiceAccountDelegationCommand(agent, alice, null));

        assertThat(expired.getRevokedAt()).isEqualTo(NOW);
        assertThat(expired.getRevokedBy()).isEqualTo(admin);
        assertThat(view.id()).isNotEqualTo(expired.getId());
        assertThat(view.status()).isEqualTo(ServiceAccountDelegationStatus.ACTIVE);
        verify(repository).saveAndFlush(expired);
    }

    @Test
    void grantTranslatesARacedUniqueViolationInto409() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertThatThrownBy(() -> service.grant(ORG, admin,
                new GrantServiceAccountDelegationCommand(agent, alice, null)))
                .isInstanceOf(ServiceAccountDelegationExistsException.class);
    }

    @Test
    void listForServiceAccountComputesStatusFromTheClock() {
        when(repository.findAllByServiceAccountUserIdAndOrganizationIdOrderByCreatedAtDesc(agent, ORG))
                .thenReturn(List.of(
                        row(NOW, null, null),
                        row(NOW, NOW.minusSeconds(1), null),
                        row(NOW, null, NOW.minusSeconds(60))));

        var views = service.listForServiceAccount(ORG, agent);

        assertThat(views).extracting(v -> v.status()).containsExactly(
                ServiceAccountDelegationStatus.ACTIVE,
                ServiceAccountDelegationStatus.EXPIRED,
                ServiceAccountDelegationStatus.REVOKED);
    }

    @Test
    void listForServiceAccountRequiresATypedAccount() {
        var human = UUID.randomUUID();
        when(serviceAccountRepository.findByUserIdAndOrganizationId(human, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForServiceAccount(ORG, human))
                .isInstanceOf(ServiceAccountNotFoundException.class);
    }

    @Test
    void listForPrincipalNeedsNoAccountCheckAndTolerantOfMissingUsers() {
        when(repository.findAllByPrincipalUserIdAndOrganizationIdOrderByCreatedAtDesc(alice, ORG))
                .thenReturn(List.of(row(NOW, null, null)));
        when(userAdminService.findByIds(eq(ORG), any())).thenReturn(Map.of());

        var views = service.listForPrincipal(ORG, alice);

        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.principalEmail()).isNull();
            assertThat(v.serviceAccountEmail()).isNull();
        });
    }

    @Test
    void listForPrincipalWithNoRowsSkipsTheUserLookup() {
        when(repository.findAllByPrincipalUserIdAndOrganizationIdOrderByCreatedAtDesc(alice, ORG))
                .thenReturn(List.of());

        assertThat(service.listForPrincipal(ORG, alice)).isEmpty();
        verify(userAdminService, never()).findByIds(any(), any());
    }

    @Test
    void revokeStampsRevokedAtAndBy() {
        var row = row(NOW, null, null);
        when(repository.findByIdAndOrganizationId(row.getId(), ORG)).thenReturn(Optional.of(row));

        var view = service.revoke(ORG, admin, row.getId(), null, null);

        assertThat(row.getRevokedAt()).isEqualTo(NOW);
        assertThat(row.getRevokedBy()).isEqualTo(admin);
        assertThat(view.status()).isEqualTo(ServiceAccountDelegationStatus.REVOKED);
        verify(repository).save(row);
    }

    @Test
    void revokeIsIdempotent() {
        var row = row(NOW, null, NOW.minusSeconds(60));
        when(repository.findByIdAndOrganizationId(row.getId(), ORG)).thenReturn(Optional.of(row));

        service.revoke(ORG, admin, row.getId(), null, null);

        assertThat(row.getRevokedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(repository, never()).save(any());
    }

    @Test
    void revokeScopedToAPrincipalRefusesSomeoneElsesGrant() {
        var row = row(NOW, null, null);
        when(repository.findByIdAndOrganizationId(row.getId(), ORG)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.revoke(ORG, admin, row.getId(), null, UUID.randomUUID()))
                .isInstanceOf(ServiceAccountDelegationNotFoundException.class);
        assertThat(service.revoke(ORG, alice, row.getId(), null, alice).revokedAt()).isEqualTo(NOW);
    }

    @Test
    void revokeScopedToAServiceAccountRefusesAnotherAccountsGrant() {
        var row = row(NOW, null, null);
        when(repository.findByIdAndOrganizationId(row.getId(), ORG)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.revoke(ORG, admin, row.getId(), UUID.randomUUID(), null))
                .isInstanceOf(ServiceAccountDelegationNotFoundException.class);
        assertThat(service.revoke(ORG, admin, row.getId(), agent, null).revokedAt()).isEqualTo(NOW);
    }

    @Test
    void revokeUnknownIs404() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(ORG, admin, id, null, null))
                .isInstanceOf(ServiceAccountDelegationNotFoundException.class)
                .extracting("delegationId").isEqualTo(id);
    }

    private ServiceAccountDelegatedPrincipalEntity row(Instant createdAt, Instant expiresAt, Instant revokedAt) {
        var row = new ServiceAccountDelegatedPrincipalEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(ORG);
        row.setServiceAccountUserId(agent);
        row.setPrincipalUserId(alice);
        row.setGrantedBy(admin);
        row.setCreatedAt(createdAt);
        row.setExpiresAt(expiresAt);
        row.setRevokedAt(revokedAt);
        return row;
    }

    private static UserView user(UUID id, boolean active, PrincipalType principalType) {
        return new UserView(id, id + "@example.com", "Someone", UserRoleType.ANALYST, UUID.randomUUID(), "ANALYST",
                ORG, active, AuthProviderType.LOCAL, "hash", null, "en", false, false, NOW, null, NOW,
                principalType);
    }
}
