package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand;
import com.bablsoft.accessflow.ai.internal.persistence.entity.LangfuseConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.LangfuseConfigRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLangfuseConfigServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock LangfuseConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock LangfuseConfigResolver configResolver;
    @Mock LangfuseClient client;
    @Mock MessageSource messageSource;

    @InjectMocks DefaultLangfuseConfigService service;

    @Test
    void getOrDefaultReturnsTransientDefaultWhenNoRow() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());

        var view = service.getOrDefault(ORG_ID);

        assertThat(view.id()).isNull();
        assertThat(view.enabled()).isFalse();
        assertThat(view.tracingEnabled()).isTrue();
        assertThat(view.promptManagementEnabled()).isFalse();
        assertThat(view.secretKeyConfigured()).isFalse();
    }

    @Test
    void getOrDefaultMapsPersistedRowAndMasksSecret() {
        var entity = seeded();
        entity.setSecretKeyEncrypted("ENC(secret)");
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));

        var view = service.getOrDefault(ORG_ID);

        assertThat(view.enabled()).isTrue();
        assertThat(view.host()).isEqualTo("https://lf.example.com");
        assertThat(view.secretKeyConfigured()).isTrue();
    }

    @Test
    void updateEncryptsSecretAndPublishesEvent() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        when(repository.save(any(LangfuseConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("sk-secret")).thenReturn("ENC(sk-secret)");

        var view = service.update(ORG_ID, new UpdateLangfuseConfigCommand(
                true, "https://lf.example.com", "pk-1", "sk-secret", true, true));

        assertThat(view.enabled()).isTrue();
        assertThat(view.promptManagementEnabled()).isTrue();
        assertThat(view.secretKeyConfigured()).isTrue();
        var captor = ArgumentCaptor.forClass(LangfuseConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSecretKeyEncrypted()).isEqualTo("ENC(sk-secret)");
        verify(eventPublisher).publishEvent(new LangfuseConfigUpdatedEvent(ORG_ID));
    }

    @Test
    void updateWithMaskedSecretLeavesCiphertextUntouched() {
        var entity = seeded();
        entity.setSecretKeyEncrypted("ENC(existing)");
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        when(repository.save(any(LangfuseConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(ORG_ID, new UpdateLangfuseConfigCommand(
                null, null, null, UpdateLangfuseConfigCommand.MASKED_SECRET, null, null));

        assertThat(entity.getSecretKeyEncrypted()).isEqualTo("ENC(existing)");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateWithBlankSecretClearsCiphertext() {
        var entity = seeded();
        entity.setSecretKeyEncrypted("ENC(existing)");
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        when(repository.save(any(LangfuseConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(ORG_ID, new UpdateLangfuseConfigCommand(null, null, null, "", null, null));

        assertThat(entity.getSecretKeyEncrypted()).isNull();
    }

    @Test
    void updateWithBlankHostClearsIt() {
        var entity = seeded();
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        when(repository.save(any(LangfuseConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(ORG_ID, new UpdateLangfuseConfigCommand(null, "  ", null, null, null, null));

        assertThat(view.host()).isNull();
    }

    @Test
    void testConnectionReturnsNotConfiguredWhenResolverEmpty() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.empty());
        when(messageSource.getMessage(eq("langfuse.test.not_configured"), any(), any(Locale.class)))
                .thenReturn("not configured");

        var result = service.testConnection(ORG_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("not configured");
        verify(client, never()).verifyConnection(any());
    }

    @Test
    void testConnectionReturnsSuccessWhenClientSucceeds() {
        var resolved = new ResolvedLangfuseConfig("https://lf.example.com/", "pk", "sk", true, false);
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved));
        when(messageSource.getMessage(eq("langfuse.test.success"), any(), any(Locale.class)))
                .thenReturn("connected");

        var result = service.testConnection(ORG_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("connected");
        verify(client).verifyConnection(resolved);
    }

    @Test
    void testConnectionReturnsErrorMessageWhenClientThrows() {
        var resolved = new ResolvedLangfuseConfig("https://lf.example.com/", "pk", "sk", true, false);
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved));
        org.mockito.Mockito.doThrow(new RuntimeException("401 Unauthorized"))
                .when(client).verifyConnection(resolved);

        var result = service.testConnection(ORG_ID);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("401 Unauthorized");
    }

    private static LangfuseConfigEntity seeded() {
        var entity = new LangfuseConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setEnabled(true);
        entity.setHost("https://lf.example.com");
        entity.setPublicKey("pk-1");
        return entity;
    }
}
