package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.UpdateAiConfigCommand;
import com.partqam.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.partqam.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiConfigServiceTest {

    @Mock AiConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;

    private DefaultAiConfigService service;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultAiConfigService(
                repository,
                encryptionService,
                new AiAnalyzerProperties(AiProviderType.ANTHROPIC));
    }

    @Test
    void getOrDefaultReturnsAnthropicDefaultsWhenNoRowExists() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        var view = service.getOrDefault(orgId);

        assertThat(view.id()).isNull();
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.provider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(view.model()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(view.endpoint()).isEqualTo("https://api.anthropic.com/v1");
        assertThat(view.apiKeyMasked()).isFalse();
        assertThat(view.enableAiDefault()).isTrue();
        assertThat(view.blockCritical()).isTrue();
        assertThat(view.includeSchema()).isTrue();
        assertThat(view.autoApproveLow()).isFalse();
    }

    @Test
    void getOrDefaultMapsExistingRowAndMasksApiKey() {
        var entity = seededEntity();
        entity.setApiKeyEncrypted("ENC(secret)");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));

        var view = service.getOrDefault(orgId);

        assertThat(view.apiKeyMasked()).isTrue();
        assertThat(view.id()).isEqualTo(entity.getId());
    }

    @Test
    void updateCreatesRowWhenNonePersistedUsingProviderDefaults() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("sk-new")).thenReturn("ENC(sk-new)");

        var command = new UpdateAiConfigCommand(
                AiProviderType.OPENAI, "gpt-4o-mini", "https://example.com/v1",
                "sk-new", 5_000, 1_000, 500, false, true, false, false);
        var view = service.update(orgId, command);

        assertThat(view.provider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(view.model()).isEqualTo("gpt-4o-mini");
        assertThat(view.endpoint()).isEqualTo("https://example.com/v1");
        assertThat(view.timeoutMs()).isEqualTo(5_000);
        assertThat(view.maxPromptTokens()).isEqualTo(1_000);
        assertThat(view.maxCompletionTokens()).isEqualTo(500);
        assertThat(view.enableAiDefault()).isFalse();
        assertThat(view.autoApproveLow()).isTrue();
        assertThat(view.blockCritical()).isFalse();
        assertThat(view.includeSchema()).isFalse();
        assertThat(view.apiKeyMasked()).isTrue();
    }

    @Test
    void updateLeavesApiKeyAloneWhenMaskedPlaceholderProvided() {
        var entity = seededEntity();
        entity.setApiKeyEncrypted("ENC(prior)");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, partial(UpdateAiConfigCommand.MASKED_API_KEY));

        var captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getApiKeyEncrypted()).isEqualTo("ENC(prior)");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateClearsApiKeyWhenBlankProvided() {
        var entity = seededEntity();
        entity.setApiKeyEncrypted("ENC(prior)");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, partial(""));

        var captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getApiKeyEncrypted()).isNull();
    }

    @Test
    void updateLeavesApiKeyAloneWhenNullProvided() {
        var entity = seededEntity();
        entity.setApiKeyEncrypted("ENC(prior)");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, partial(null));

        var captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getApiKeyEncrypted()).isEqualTo("ENC(prior)");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateClearsEndpointWhenBlank() {
        var entity = seededEntity();
        entity.setEndpoint("https://prior");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var command = new UpdateAiConfigCommand(null, null, "  ", null,
                null, null, null, null, null, null, null);
        var view = service.update(orgId, command);

        assertThat(view.endpoint()).isNull();
    }

    private UpdateAiConfigCommand partial(String apiKey) {
        return new UpdateAiConfigCommand(null, null, null, apiKey,
                null, null, null, null, null, null, null);
    }

    private AiConfigEntity seededEntity() {
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setProvider(AiProviderType.ANTHROPIC);
        entity.setModel("claude-sonnet-4-20250514");
        entity.setEndpoint("https://api.anthropic.com/v1");
        entity.setTimeoutMs(30_000);
        entity.setMaxPromptTokens(8_000);
        entity.setMaxCompletionTokens(2_000);
        entity.setEnableAiDefault(true);
        entity.setAutoApproveLow(false);
        entity.setBlockCritical(true);
        entity.setIncludeSchema(true);
        return entity;
    }
}
