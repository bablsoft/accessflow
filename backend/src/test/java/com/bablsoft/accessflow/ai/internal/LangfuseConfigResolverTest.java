package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.LangfuseProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.LangfuseConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.LangfuseConfigRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LangfuseConfigResolverTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock LangfuseConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;

    private LangfuseConfigResolver resolver() {
        return new LangfuseConfigResolver(repository, encryptionService,
                new LangfuseProperties(URI.create("https://cloud.langfuse.com"), null, null, null));
    }

    @Test
    void emptyWhenNoRow() {
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.empty());
        assertThat(resolver().resolve(ORG_ID)).isEmpty();
    }

    @Test
    void emptyWhenDisabled() {
        var entity = entity(false, "pk", "ENC", null);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        assertThat(resolver().resolve(ORG_ID)).isEmpty();
    }

    @Test
    void emptyWhenCredentialsIncomplete() {
        var entity = entity(true, "pk", null, null);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        assertThat(resolver().resolve(ORG_ID)).isEmpty();
    }

    @Test
    void emptyWhenOrganizationNull() {
        assertThat(resolver().resolve(null)).isEmpty();
    }

    @Test
    void resolvesWithDefaultHostWhenBlank() {
        var entity = entity(true, "pk", "ENC", null);
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC")).thenReturn("sk");

        var resolved = resolver().resolve(ORG_ID).orElseThrow();

        assertThat(resolved.host()).isEqualTo("https://cloud.langfuse.com/");
        assertThat(resolved.publicKey()).isEqualTo("pk");
        assertThat(resolved.secretKey()).isEqualTo("sk");
        assertThat(resolved.tracingEnabled()).isTrue();
    }

    @Test
    void normalizesCustomHostTrailingSlash() {
        var entity = entity(true, "pk", "ENC", "https://lf.example.com");
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC")).thenReturn("sk");

        var resolved = resolver().resolve(ORG_ID).orElseThrow();

        assertThat(resolved.host()).isEqualTo("https://lf.example.com/");
    }

    @Test
    void cachesAndEvictsOnEvent() {
        var entity = entity(true, "pk", "ENC", "https://lf.example.com/");
        when(repository.findByOrganizationId(ORG_ID)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC")).thenReturn("sk");
        var resolver = resolver();

        resolver.resolve(ORG_ID);
        resolver.resolve(ORG_ID);
        verify(repository, times(1)).findByOrganizationId(ORG_ID);

        resolver.onConfigUpdated(new LangfuseConfigUpdatedEvent(ORG_ID));
        resolver.resolve(ORG_ID);
        verify(repository, times(2)).findByOrganizationId(ORG_ID);
    }

    private static LangfuseConfigEntity entity(boolean enabled, String publicKey, String secretEnc, String host) {
        var entity = new LangfuseConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setEnabled(enabled);
        entity.setPublicKey(publicKey);
        entity.setSecretKeyEncrypted(secretEnc);
        entity.setHost(host);
        entity.setTracingEnabled(true);
        entity.setPromptManagementEnabled(false);
        return entity;
    }
}
