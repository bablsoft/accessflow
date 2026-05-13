package com.bablsoft.accessflow.mcp.internal.service;

import com.bablsoft.accessflow.mcp.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.mcp.api.ApiKeyNotFoundException;
import com.bablsoft.accessflow.mcp.internal.auth.ApiKeyHasher;
import com.bablsoft.accessflow.mcp.internal.persistence.entity.ApiKeyEntity;
import com.bablsoft.accessflow.mcp.internal.persistence.repo.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
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
