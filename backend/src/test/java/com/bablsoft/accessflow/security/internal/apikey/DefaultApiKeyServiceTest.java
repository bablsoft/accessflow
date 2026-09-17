package com.bablsoft.accessflow.security.internal.apikey;

import com.bablsoft.accessflow.security.api.ApiKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.security.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.security.api.ApiKeyNotFoundException;
import com.bablsoft.accessflow.security.api.ResolvedApiKey;
import com.bablsoft.accessflow.security.internal.persistence.entity.ApiKeyEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
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
class DefaultApiKeyServiceTest {

    @Mock ApiKeyRepository apiKeyRepository;

    @InjectMocks DefaultApiKeyService service;

    private UUID userId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
    }

    @Test
    void issue_persists_a_new_key_and_returns_the_raw_value_once() {
        when(apiKeyRepository.existsByUserIdAndName(userId, "ci")).thenReturn(false);
        when(apiKeyRepository.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var issued = service.issue(userId, orgId, "ci", null);

        assertThat(issued.rawKey()).startsWith(ApiKeyHasher.PREFIX);
        assertThat(issued.view().name()).isEqualTo("ci");
        assertThat(issued.view().keyPrefix()).hasSize(ApiKeyHasher.PREFIX_LENGTH);

        var captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getKeyHash()).isEqualTo(ApiKeyHasher.hash(issued.rawKey()));
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().getRevokedAt()).isNull();
    }

    @Test
    void issue_rejects_duplicate_name_for_same_user() {
        when(apiKeyRepository.existsByUserIdAndName(userId, "ci")).thenReturn(true);
        assertThatThrownBy(() -> service.issue(userId, orgId, "ci", null))
                .isInstanceOf(ApiKeyDuplicateNameException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void importOrUpdate_creates_a_new_row_storing_the_supplied_keys_hash() {
        var raw = ApiKeyHasher.generate();
        when(apiKeyRepository.findByUserIdAndName(userId, "terraform")).thenReturn(Optional.empty());
        when(apiKeyRepository.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.importOrUpdate(userId, orgId, "terraform", raw, null);

        assertThat(view.name()).isEqualTo("terraform");
        var captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getKeyHash()).isEqualTo(ApiKeyHasher.hash(raw));
        assertThat(captor.getValue().getKeyPrefix()).isEqualTo(ApiKeyHasher.prefixOf(raw));
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().isBootstrapDeclared()).isTrue();
        assertThat(view.bootstrapDeclared()).isTrue();
        verify(apiKeyRepository).clearBootstrapDeclaredForOtherKeys(userId, captor.getValue().getId());
    }

    @Test
    void importOrUpdate_overwrites_existing_key_in_place_and_clears_revocation() {
        var existing = newEntity(userId);
        existing.setRevokedAt(Instant.now().minusSeconds(60));
        var originalId = existing.getId();
        var raw = ApiKeyHasher.generate();
        when(apiKeyRepository.findByUserIdAndName(userId, "ci")).thenReturn(Optional.of(existing));
        when(apiKeyRepository.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.importOrUpdate(userId, orgId, "ci", raw, null);

        assertThat(view.id()).isEqualTo(originalId);
        assertThat(existing.getKeyHash()).isEqualTo(ApiKeyHasher.hash(raw));
        assertThat(existing.getRevokedAt()).isNull();
        assertThat(existing.isBootstrapDeclared()).isTrue();
        verify(apiKeyRepository).save(existing);
        // A renamed api-key-name must demote the previous declared row — at most one per account.
        verify(apiKeyRepository).clearBootstrapDeclaredForOtherKeys(userId, originalId);
    }

    @Test
    void importOrUpdate_rejects_a_key_without_the_expected_prefix_before_touching_the_repository() {
        assertThatThrownBy(() -> service.importOrUpdate(userId, orgId, "ci", "not-a-key", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(apiKeyRepository, never()).clearBootstrapDeclaredForOtherKeys(any(), any());
    }

    @Test
    void listByUserIds_groups_views_by_owner_newest_first() {
        var other = UUID.randomUUID();
        var mine = newEntity(userId);
        var theirs = newEntity(other);
        when(apiKeyRepository.findByUserIdInOrderByCreatedAtDesc(Set.of(userId, other)))
                .thenReturn(List.of(mine, theirs));

        var result = service.listByUserIds(Set.of(userId, other));

        assertThat(result).containsOnlyKeys(userId, other);
        assertThat(result.get(userId)).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo(mine.getId());
            assertThat(v.bootstrapDeclared()).isFalse();
        });
        assertThat(result.get(other)).singleElement().extracting(v -> v.id()).isEqualTo(theirs.getId());
    }

    @Test
    void listByUserIds_short_circuits_on_an_empty_input() {
        assertThat(service.listByUserIds(List.of())).isEmpty();
        verify(apiKeyRepository, never()).findByUserIdInOrderByCreatedAtDesc(any());
    }

    @Test
    void revoke_refuses_a_bootstrap_declared_key() {
        var entity = newEntity(userId);
        entity.setBootstrapDeclared(true);
        when(apiKeyRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.revoke(userId, entity.getId()))
                .isInstanceOf(ApiKeyBootstrapDeclaredException.class)
                .satisfies(ex -> assertThat(((ApiKeyBootstrapDeclaredException) ex).apiKeyId())
                        .isEqualTo(entity.getId()));
        assertThat(entity.getRevokedAt()).isNull();
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void expireAt_sets_the_expiry_and_leaves_revocation_alone() {
        var entity = newEntity(userId);
        var until = Instant.now().plusSeconds(3600);
        when(apiKeyRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        service.expireAt(userId, entity.getId(), until);

        assertThat(entity.getExpiresAt()).isEqualTo(until);
        assertThat(entity.getRevokedAt()).isNull();
        verify(apiKeyRepository).save(entity);
    }

    @Test
    void expireAt_refuses_a_bootstrap_declared_key() {
        var entity = newEntity(userId);
        entity.setBootstrapDeclared(true);
        when(apiKeyRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.expireAt(userId, entity.getId(), Instant.now()))
                .isInstanceOf(ApiKeyBootstrapDeclaredException.class);
        assertThat(entity.getExpiresAt()).isNull();
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void expireAt_throws_not_found_when_key_is_unknown() {
        var unknown = UUID.randomUUID();
        when(apiKeyRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.expireAt(userId, unknown, Instant.now()))
                .isInstanceOf(ApiKeyNotFoundException.class);
    }

    @Test
    void expireAt_treats_other_users_key_as_not_found() {
        var entity = newEntity(UUID.randomUUID());
        when(apiKeyRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.expireAt(userId, entity.getId(), Instant.now()))
                .isInstanceOf(ApiKeyNotFoundException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void importOrUpdate_rejects_a_key_without_the_expected_prefix() {
        assertThatThrownBy(() -> service.importOrUpdate(userId, orgId, "ci", "not-a-key", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void list_maps_entities_to_views_and_drops_hash() {
        var entity = newEntity(userId);
        when(apiKeyRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity));
        var result = service.list(userId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo(entity.getName());
        assertThat(result.get(0).keyPrefix()).isEqualTo(entity.getKeyPrefix());
    }

    @Test
    void revoke_sets_revoked_at_when_owned_by_caller() {
        var entity = newEntity(userId);
        when(apiKeyRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        service.revoke(userId, entity.getId());

        assertThat(entity.getRevokedAt()).isNotNull();
        verify(apiKeyRepository).save(entity);
    }

    @Test
    void revoke_is_idempotent_when_already_revoked() {
        var entity = newEntity(userId);
        var existing = Instant.now().minusSeconds(60);
        entity.setRevokedAt(existing);
        when(apiKeyRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        service.revoke(userId, entity.getId());

        assertThat(entity.getRevokedAt()).isEqualTo(existing);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void revoke_throws_not_found_when_key_is_unknown() {
        var unknown = UUID.randomUUID();
        when(apiKeyRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(userId, unknown))
                .isInstanceOf(ApiKeyNotFoundException.class);
    }

    @Test
    void revoke_treats_other_users_key_as_not_found() {
        var entity = newEntity(UUID.randomUUID()); // owned by someone else
        when(apiKeyRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.revoke(userId, entity.getId()))
                .isInstanceOf(ApiKeyNotFoundException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void resolve_returns_key_and_user_ids_for_valid_key_and_touches_last_used() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));

        var result = service.resolve(raw);

        assertThat(result).contains(new ResolvedApiKey(entity.getId(), userId));
        verify(apiKeyRepository).touchLastUsedAt(eq(entity.getId()), any(Instant.class));
    }

    @Test
    void resolve_still_succeeds_when_last_used_touch_fails() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(apiKeyRepository).touchLastUsedAt(eq(entity.getId()), any(Instant.class));

        assertThat(service.resolve(raw)).contains(new ResolvedApiKey(entity.getId(), userId));
    }

    @Test
    void resolve_empty_for_malformed_input() {
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("nope")).isEmpty();
        assertThat(service.resolve("af_")).isEmpty();
        verify(apiKeyRepository, never()).findByKeyHash(any());
    }

    @Test
    void resolve_empty_for_unknown_hash() {
        var raw = ApiKeyHasher.generate();
        when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash(raw))).thenReturn(Optional.empty());
        assertThat(service.resolve(raw)).isEmpty();
    }

    @Test
    void resolve_empty_for_revoked_key() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        entity.setRevokedAt(Instant.now().minusSeconds(60));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));
        assertThat(service.resolve(raw)).isEmpty();
        verify(apiKeyRepository, never()).touchLastUsedAt(any(), any());
    }

    @Test
    void resolve_empty_for_expired_key() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        entity.setExpiresAt(Instant.now().minusSeconds(60));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));
        assertThat(service.resolve(raw)).isEmpty();
        verify(apiKeyRepository, never()).touchLastUsedAt(any(), any());
    }

    @Test
    void resolve_honours_a_future_expiry() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));
        assertThat(service.resolve(raw)).isPresent();
    }

    // resolveUserId is now the interface default over resolve(); the resolveUserId_* tests
    // below are kept verbatim from before #869 to prove its behaviour is unchanged.

    @Test
    void resolveUserId_returns_user_for_valid_key_and_touches_last_used() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));

        var result = service.resolveUserId(raw);

        assertThat(result).contains(userId);
        verify(apiKeyRepository).touchLastUsedAt(eq(entity.getId()), any(Instant.class));
    }

    @Test
    void resolveUserId_empty_for_malformed_input() {
        assertThat(service.resolveUserId(null)).isEmpty();
        assertThat(service.resolveUserId("nope")).isEmpty();
        assertThat(service.resolveUserId("af_")).isEmpty();
    }

    @Test
    void resolveUserId_empty_for_unknown_hash() {
        var raw = ApiKeyHasher.generate();
        when(apiKeyRepository.findByKeyHash(ApiKeyHasher.hash(raw))).thenReturn(Optional.empty());
        assertThat(service.resolveUserId(raw)).isEmpty();
    }

    @Test
    void resolveUserId_empty_for_revoked_key() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        entity.setRevokedAt(Instant.now().minusSeconds(60));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));
        assertThat(service.resolveUserId(raw)).isEmpty();
    }

    @Test
    void resolveUserId_empty_for_expired_key() {
        var raw = ApiKeyHasher.generate();
        var entity = newEntity(userId);
        entity.setKeyHash(ApiKeyHasher.hash(raw));
        entity.setExpiresAt(Instant.now().minusSeconds(60));
        when(apiKeyRepository.findByKeyHash(entity.getKeyHash())).thenReturn(Optional.of(entity));
        assertThat(service.resolveUserId(raw)).isEmpty();
    }

    private ApiKeyEntity newEntity(UUID owner) {
        var entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setUserId(owner);
        entity.setName("ci");
        entity.setKeyPrefix("af_demoxxxxx");
        entity.setKeyHash("hash" + UUID.randomUUID());
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
