package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.scim.api.ScimTokenNameConflictException;
import com.bablsoft.accessflow.scim.api.ScimTokenNotFoundException;
import com.bablsoft.accessflow.scim.internal.persistence.entity.ScimTokenEntity;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultScimTokenServiceTest {

    @Mock ScimTokenRepository tokenRepository;

    DefaultScimTokenService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultScimTokenService(tokenRepository);
    }

    @Test
    void createReturnsRawTokenOnceAndStoresOnlyTheHash() {
        when(tokenRepository.existsByOrganizationIdAndName(orgId, "okta-prod")).thenReturn(false);
        when(tokenRepository.save(any(ScimTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var issued = service.create(orgId, "okta-prod", creatorId);

        assertThat(issued.rawToken()).startsWith("af_scim_");
        assertThat(issued.token().tokenPrefix()).isEqualTo(issued.rawToken().substring(0, 12));
        assertThat(issued.token().name()).isEqualTo("okta-prod");
        assertThat(issued.token().revokedAt()).isNull();
    }

    @Test
    void createRejectsDuplicateName() {
        when(tokenRepository.existsByOrganizationIdAndName(orgId, "okta-prod")).thenReturn(true);

        assertThatThrownBy(() -> service.create(orgId, " okta-prod ", creatorId))
                .isInstanceOf(ScimTokenNameConflictException.class);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void authenticateResolvesActiveTokenByHash() {
        when(tokenRepository.existsByOrganizationIdAndName(orgId, "t")).thenReturn(false);
        when(tokenRepository.save(any(ScimTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var issued = service.create(orgId, "t", creatorId);
        var entity = new ScimTokenEntity();
        entity.setId(issued.token().id());
        entity.setOrganizationId(orgId);
        entity.setName("t");
        entity.setTokenHash(ScimTokenHasher.hash(issued.rawToken()));
        when(tokenRepository.findByTokenHash(ScimTokenHasher.hash(issued.rawToken())))
                .thenReturn(Optional.of(entity));

        var principal = service.authenticate(issued.rawToken());

        assertThat(principal).isPresent();
        assertThat(principal.get().organizationId()).isEqualTo(orgId);
        assertThat(principal.get().tokenName()).isEqualTo("t");
        assertThat(entity.getLastUsedAt()).isNotNull();
    }

    @Test
    void authenticateRejectsRevokedToken() {
        var entity = new ScimTokenEntity();
        entity.setOrganizationId(orgId);
        entity.setRevokedAt(Instant.now());
        var raw = ScimTokenHasher.generate();
        when(tokenRepository.findByTokenHash(ScimTokenHasher.hash(raw)))
                .thenReturn(Optional.of(entity));

        assertThat(service.authenticate(raw)).isEmpty();
    }

    @Test
    void authenticateRejectsUnknownAndMalformedTokens() {
        var raw = ScimTokenHasher.generate();
        when(tokenRepository.findByTokenHash(ScimTokenHasher.hash(raw)))
                .thenReturn(Optional.empty());

        assertThat(service.authenticate(raw)).isEmpty();
        assertThat(service.authenticate("not-a-scim-token")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
        verify(tokenRepository).findByTokenHash(any());
    }

    @Test
    void revokeIsIdempotent() {
        var tokenId = UUID.randomUUID();
        var entity = new ScimTokenEntity();
        entity.setId(tokenId);
        entity.setOrganizationId(orgId);
        entity.setRevokedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(tokenRepository.findByOrganizationIdAndId(orgId, tokenId))
                .thenReturn(Optional.of(entity));

        service.revoke(orgId, tokenId);

        assertThat(entity.getRevokedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void revokeUnknownTokenThrows() {
        var tokenId = UUID.randomUUID();
        when(tokenRepository.findByOrganizationIdAndId(orgId, tokenId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(orgId, tokenId))
                .isInstanceOf(ScimTokenNotFoundException.class);
    }

    @Test
    void listMapsEntitiesToViews() {
        var entity = new ScimTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setName("entra");
        entity.setTokenPrefix("af_scim_AbCd");
        when(tokenRepository.findAllByOrganizationIdOrderByCreatedAtDesc(orgId))
                .thenReturn(java.util.List.of(entity));

        var views = service.list(orgId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).name()).isEqualTo("entra");
        assertThat(views.get(0).tokenPrefix()).isEqualTo("af_scim_AbCd");
    }
}
