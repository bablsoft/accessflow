package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateReviewDelegationCommand;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.IllegalReviewDelegationException;
import com.bablsoft.accessflow.core.api.ReviewDelegationNotFoundException;
import com.bablsoft.accessflow.core.api.ReviewDelegationScopeResolver;
import com.bablsoft.accessflow.core.api.ReviewDelegationStatus;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDelegationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDelegationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultReviewDelegationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    @Mock
    private ReviewDelegationRepository delegationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReviewDelegationScopeResolver datasourceResolver;

    private DefaultReviewDelegationService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID delegatorId = UUID.randomUUID();
    private final UUID delegateId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(datasourceResolver.supportedKind()).thenReturn(DelegationScopeKind.DATASOURCE);
        // Echo the key back so assertions can name the rule that failed without pinning copy.
        var messageSource = new org.springframework.context.support.StaticMessageSource();
        messageSource.setUseCodeAsDefaultMessage(true);
        service = new DefaultReviewDelegationService(delegationRepository, userRepository,
                List.of(datasourceResolver), Clock.fixed(NOW, ZoneOffset.UTC), messageSource,
                new com.bablsoft.accessflow.core.internal.config.ReviewDelegationProperties(10));
        activeUser(delegatorId);
        activeUser(delegateId);
        when(delegationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(delegationRepository.countOpenForDelegator(any(), any(), any())).thenReturn(0L);
    }

    private void activeUser(UUID id) {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var user = new UserEntity();
        user.setId(id);
        user.setActive(true);
        user.setEmail(id + "@example.com");
        user.setOrganization(org);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
    }

    private CreateReviewDelegationCommand command(DelegationScopeKind kind, UUID scopeId) {
        return new CreateReviewDelegationCommand(orgId, delegatorId, delegateId, kind, scopeId,
                "Annual leave", NOW.plus(1, ChronoUnit.DAYS), NOW.plus(8, ChronoUnit.DAYS));
    }

    @Test
    void createsAnUnrestrictedDelegationAsScheduledBeforeItsWindowOpens() {
        var view = service.create(command(null, null));

        assertThat(view.delegatorUserId()).isEqualTo(delegatorId);
        assertThat(view.delegateUserId()).isEqualTo(delegateId);
        assertThat(view.scopeKind()).isNull();
        assertThat(view.scopeName()).isNull();
        assertThat(view.status()).isEqualTo(ReviewDelegationStatus.SCHEDULED);
    }

    @Test
    void createsAScopedDelegationAndResolvesItsDisplayName() {
        when(datasourceResolver.resolveName(orgId, datasourceId)).thenReturn(Optional.of("Prod PG"));

        var view = service.create(command(DelegationScopeKind.DATASOURCE, datasourceId));

        assertThat(view.scopeKind()).isEqualTo(DelegationScopeKind.DATASOURCE);
        assertThat(view.scopeName()).isEqualTo("Prod PG");
    }

    @Test
    void rejectsAScopeThatDoesNotResolveInTheOrganization() {
        when(datasourceResolver.resolveName(orgId, datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command(DelegationScopeKind.DATASOURCE, datasourceId)))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("scope_unresolved");
        verify(delegationRepository, never()).save(any());
    }

    @Test
    void failsClosedWhenNoResolverIsRegisteredForTheScopeKind() {
        // Every kind has a resolver in production (core supplies DATASOURCE, apigov supplies
        // API_CONNECTOR), so this asserts the defensive branch explicitly rather than relying on a
        // gap: a future scope kind added without its resolver must be refused, not accepted blind.
        var messageSource = new org.springframework.context.support.StaticMessageSource();
        messageSource.setUseCodeAsDefaultMessage(true);
        var withoutResolvers = new DefaultReviewDelegationService(delegationRepository,
                userRepository, List.of(), Clock.fixed(NOW, ZoneOffset.UTC), messageSource,
                new com.bablsoft.accessflow.core.internal.config.ReviewDelegationProperties(10));

        assertThatThrownBy(() ->
                withoutResolvers.create(command(DelegationScopeKind.DATASOURCE, datasourceId)))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("scope_unresolved");
    }

    @Test
    void rejectsAHalfSpecifiedScope() {
        var half = new CreateReviewDelegationCommand(orgId, delegatorId, delegateId,
                DelegationScopeKind.DATASOURCE, null, null,
                NOW.plus(1, ChronoUnit.DAYS), NOW.plus(8, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.create(half))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("scope_incomplete");
    }

    @Test
    void rejectsDelegatingToYourself() {
        var toSelf = new CreateReviewDelegationCommand(orgId, delegatorId, delegatorId, null, null,
                null, NOW.plus(1, ChronoUnit.DAYS), NOW.plus(8, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.create(toSelf))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("review_delegation.self");
    }

    @Test
    void rejectsAnInvertedWindow() {
        var inverted = new CreateReviewDelegationCommand(orgId, delegatorId, delegateId, null, null,
                null, NOW.plus(8, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.create(inverted))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("window_inverted");
    }

    @Test
    void rejectsAWindowThatHasAlreadyClosed() {
        var past = new CreateReviewDelegationCommand(orgId, delegatorId, delegateId, null, null,
                null, NOW.minus(8, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> service.create(past))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("window_closed");
    }

    @Test
    void rejectsADelegateOutsideTheOrganization() {
        var foreignOrg = new OrganizationEntity();
        foreignOrg.setId(UUID.randomUUID());
        var foreign = new UserEntity();
        foreign.setId(delegateId);
        foreign.setActive(true);
        foreign.setOrganization(foreignOrg);
        when(userRepository.findById(delegateId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.create(command(null, null)))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("delegate_not_member");
    }

    @Test
    void rejectsADeactivatedDelegate() {
        var inactive = new UserEntity();
        inactive.setId(delegateId);
        inactive.setActive(false);
        when(userRepository.findById(delegateId)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.create(command(null, null)))
                .isInstanceOf(IllegalReviewDelegationException.class);
    }

    @Test
    void rejectsCreationPastTheOpenDelegationCap() {
        when(delegationRepository.countOpenForDelegator(orgId, delegatorId, NOW)).thenReturn(10L);

        assertThatThrownBy(() -> service.create(command(null, null)))
                .isInstanceOf(IllegalReviewDelegationException.class)
                .hasMessageContaining("cap_reached");
    }

    @Test
    void revokeStampsTheRevokerAndTime() {
        var entity = persisted();
        when(delegationRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        assertThat(service.revoke(entity.getId(), orgId, delegatorId)).isTrue();

        assertThat(entity.getRevokedAt()).isEqualTo(NOW);
        assertThat(entity.getRevokedBy()).isEqualTo(delegatorId);
        verify(delegationRepository).save(entity);
    }

    @Test
    void revokeIsIdempotent() {
        var entity = persisted();
        entity.setRevokedAt(NOW.minus(1, ChronoUnit.DAYS));
        when(delegationRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        // Returns false so the caller can skip auditing an event that did not happen.
        assertThat(service.revoke(entity.getId(), orgId, delegatorId)).isFalse();

        assertThat(entity.getRevokedAt()).isEqualTo(NOW.minus(1, ChronoUnit.DAYS));
        verify(delegationRepository, never()).save(any());
    }

    @Test
    void theDelegateCannotRevokeADelegationGrantedToThem() {
        var entity = persisted();
        when(delegationRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.revoke(entity.getId(), orgId, delegateId))
                .isInstanceOf(ReviewDelegationNotFoundException.class);
        verify(delegationRepository, never()).save(any());
    }

    @Test
    void revokingAnUnknownDelegationThrows() {
        var id = UUID.randomUUID();
        when(delegationRepository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(id, orgId, delegatorId))
                .isInstanceOf(ReviewDelegationNotFoundException.class);
    }

    @Test
    void listGrantedByDerivesActiveAndExpiredStatuses() {
        var active = persisted();
        var expired = persisted();
        expired.setStartsAt(NOW.minus(8, ChronoUnit.DAYS));
        expired.setEndsAt(NOW.minus(1, ChronoUnit.DAYS));
        when(delegationRepository.findByOrganizationIdAndDelegatorIdOrderByCreatedAtDesc(orgId, delegatorId))
                .thenReturn(List.of(active, expired));

        var views = service.listGrantedBy(orgId, delegatorId);

        assertThat(views).extracting(v -> v.status())
                .containsExactly(ReviewDelegationStatus.ACTIVE, ReviewDelegationStatus.EXPIRED);
    }

    @Test
    void listReceivedByReadsTheDelegateSide() {
        when(delegationRepository.findByOrganizationIdAndDelegateIdOrderByCreatedAtDesc(orgId, delegateId))
                .thenReturn(List.of(persisted()));

        assertThat(service.listReceivedBy(orgId, delegateId)).hasSize(1);
    }

    @Test
    void listForOrganizationPagesThroughTheSearchQuery() {
        when(delegationRepository.search(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(persisted())));

        var page = service.listForOrganization(orgId,
                com.bablsoft.accessflow.core.api.ReviewDelegationFilter.none(),
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
    }

    private ReviewDelegationEntity persisted() {
        var entity = new ReviewDelegationEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDelegatorId(delegatorId);
        entity.setDelegateId(delegateId);
        entity.setStartsAt(NOW.minus(1, ChronoUnit.DAYS));
        entity.setEndsAt(NOW.plus(1, ChronoUnit.DAYS));
        entity.setCreatedBy(delegatorId);
        return entity;
    }
}
