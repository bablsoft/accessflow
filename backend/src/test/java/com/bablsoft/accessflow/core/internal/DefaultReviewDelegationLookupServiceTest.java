package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDelegationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDelegationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReviewDelegationLookupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Mock
    private ReviewDelegationRepository delegationRepository;
    @Mock
    private UserRepository userRepository;

    private DefaultReviewDelegationLookupService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID delegateId = UUID.randomUUID();
    private final UUID delegatorId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultReviewDelegationLookupService(delegationRepository, userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ReviewDelegationEntity delegation(DelegationScopeKind kind, UUID scopeId) {
        var entity = new ReviewDelegationEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDelegatorId(delegatorId);
        entity.setDelegateId(delegateId);
        entity.setScopeKind(kind);
        entity.setScopeId(scopeId);
        return entity;
    }

    private UserEntity delegator(String roleName) {
        var user = new UserEntity();
        user.setId(delegatorId);
        var role = new com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity();
        role.setName(roleName);
        user.setRoleRef(role);
        return user;
    }

    @Test
    void resolvesUnrestrictedDelegationWithTheDelegatorsLiveRoleName() {
        when(delegationRepository.findActiveForDelegate(orgId, delegateId, NOW))
                .thenReturn(List.of(delegation(null, null)));
        when(userRepository.findAllById(List.of(delegatorId)))
                .thenReturn(List.of(delegator("REVIEWER")));

        var identities = service.findActiveForDelegate(orgId, delegateId,
                DelegationScopeKind.DATASOURCE, datasourceId);

        assertThat(identities).singleElement().satisfies(identity -> {
            assertThat(identity.delegatorUserId()).isEqualTo(delegatorId);
            assertThat(identity.delegatorRoleName()).isEqualTo("REVIEWER");
            assertThat(identity.isUnrestricted()).isTrue();
        });
    }

    @Test
    void scopedDelegationMatchesOnlyItsOwnResource() {
        when(delegationRepository.findActiveForDelegate(orgId, delegateId, NOW))
                .thenReturn(List.of(delegation(DelegationScopeKind.DATASOURCE, datasourceId)));

        var other = service.findActiveForDelegate(orgId, delegateId,
                DelegationScopeKind.DATASOURCE, UUID.randomUUID());

        assertThat(other).isEmpty();
        // No user lookup when nothing survived the scope filter.
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void scopedDelegationDoesNotMatchAnotherScopeKind() {
        when(delegationRepository.findActiveForDelegate(orgId, delegateId, NOW))
                .thenReturn(List.of(delegation(DelegationScopeKind.DATASOURCE, datasourceId)));

        assertThat(service.findActiveForDelegate(orgId, delegateId,
                DelegationScopeKind.API_CONNECTOR, datasourceId)).isEmpty();
    }

    @Test
    void scopedDelegationFailsClosedWhenTheRequestCarriesNoResource() {
        when(delegationRepository.findActiveForDelegate(orgId, delegateId, NOW))
                .thenReturn(List.of(delegation(DelegationScopeKind.DATASOURCE, datasourceId)));

        assertThat(service.findActiveForDelegate(orgId, delegateId,
                DelegationScopeKind.DATASOURCE, null)).isEmpty();
    }

    @Test
    void nullResourceKindReturnsEveryActiveDelegationForCallerSideFiltering() {
        when(delegationRepository.findActiveForDelegate(orgId, delegateId, NOW))
                .thenReturn(List.of(delegation(DelegationScopeKind.DATASOURCE, datasourceId)));
        when(userRepository.findAllById(List.of(delegatorId)))
                .thenReturn(List.of(delegator("REVIEWER")));

        // The grouped-request case: a bundle mixes resource kinds, so it filters per-member itself.
        assertThat(service.findActiveForDelegate(orgId, delegateId, null, null)).hasSize(1);
    }

    @Test
    void returnsEmptyWithoutTouchingUsersWhenThereAreNoDelegations() {
        when(delegationRepository.findActiveForDelegate(orgId, delegateId, NOW))
                .thenReturn(List.of());

        assertThat(service.findActiveForDelegate(orgId, delegateId, null, null)).isEmpty();
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void fallsBackToTheLegacySystemRoleWhenNoCustomRoleIsAssigned() {
        var user = new UserEntity();
        user.setId(delegatorId);
        user.setRole(UserRoleType.ADMIN);
        when(delegationRepository.findActiveForDelegate(orgId, delegateId, NOW))
                .thenReturn(List.of(delegation(null, null)));
        when(userRepository.findAllById(List.of(delegatorId))).thenReturn(List.of(user));

        assertThat(service.findActiveForDelegate(orgId, delegateId, null, null))
                .singleElement()
                .satisfies(identity -> assertThat(identity.delegatorRoleName()).isEqualTo("ADMIN"));
    }
}
