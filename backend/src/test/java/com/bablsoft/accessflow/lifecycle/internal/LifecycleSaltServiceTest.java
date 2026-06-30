package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleSaltEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleSaltRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LifecycleSaltServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    private LifecycleSaltRepository repository;
    @Mock
    private CredentialEncryptionService credentialEncryptionService;

    private LifecycleSaltService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        service = new LifecycleSaltService(repository, credentialEncryptionService, clock);
    }

    @Test
    void currentSalt_createsOnFirstUse() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        when(credentialEncryptionService.encrypt(anyString())).thenReturn("enc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credentialEncryptionService.decrypt("enc")).thenReturn("plain-salt");

        assertThat(service.currentSalt(ORG)).isEqualTo("plain-salt");
        verify(repository).save(any(LifecycleSaltEntity.class));
    }

    @Test
    void currentSalt_returnsExisting() {
        var entity = new LifecycleSaltEntity();
        entity.setOrganizationId(ORG);
        entity.setSaltEncrypted("enc");
        when(repository.findById(ORG)).thenReturn(Optional.of(entity));
        when(credentialEncryptionService.decrypt("enc")).thenReturn("existing");

        assertThat(service.currentSalt(ORG)).isEqualTo("existing");
    }

    @Test
    void rotate_bumpsVersionAndReencrypts() {
        var entity = new LifecycleSaltEntity();
        entity.setOrganizationId(ORG);
        entity.setSaltEncrypted("old");
        entity.setVersion(1);
        when(repository.findById(ORG)).thenReturn(Optional.of(entity));
        when(credentialEncryptionService.encrypt(anyString())).thenReturn("new-enc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rotate(ORG);

        assertThat(entity.getVersion()).isEqualTo(2);
        assertThat(entity.getSaltEncrypted()).isEqualTo("new-enc");
    }
}
