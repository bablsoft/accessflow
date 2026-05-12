package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiConfigLookupServiceTest {

    @Mock AiConfigRepository aiConfigRepository;
    @InjectMocks DefaultAiConfigLookupService service;

    @Test
    void returnsFalseWhenOrganizationIdIsNull() {
        assertThat(service.hasAnyUsableAiConfig(null)).isFalse();
        verifyNoInteractions(aiConfigRepository);
    }

    @Test
    void returnsFalseWhenNoConfigsExist() {
        var orgId = UUID.randomUUID();
        when(aiConfigRepository.findAllByOrganizationIdOrderByNameAsc(orgId)).thenReturn(List.of());

        assertThat(service.hasAnyUsableAiConfig(orgId)).isFalse();
    }

    @Test
    void returnsTrueForOllamaConfigWithoutApiKey() {
        var orgId = UUID.randomUUID();
        when(aiConfigRepository.findAllByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(entity(AiProviderType.OLLAMA, null)));

        assertThat(service.hasAnyUsableAiConfig(orgId)).isTrue();
    }

    @Test
    void returnsTrueForOpenAiConfigWithApiKey() {
        var orgId = UUID.randomUUID();
        when(aiConfigRepository.findAllByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(entity(AiProviderType.OPENAI, "ENC(k)")));

        assertThat(service.hasAnyUsableAiConfig(orgId)).isTrue();
    }

    @Test
    void returnsFalseForAnthropicConfigWithBlankApiKey() {
        var orgId = UUID.randomUUID();
        when(aiConfigRepository.findAllByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(entity(AiProviderType.ANTHROPIC, "   ")));

        assertThat(service.hasAnyUsableAiConfig(orgId)).isFalse();
    }

    @Test
    void returnsTrueIfAnyConfigIsUsable() {
        var orgId = UUID.randomUUID();
        when(aiConfigRepository.findAllByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(
                        entity(AiProviderType.OPENAI, null),
                        entity(AiProviderType.ANTHROPIC, "ENC(k)")));

        assertThat(service.hasAnyUsableAiConfig(orgId)).isTrue();
    }

    private static AiConfigEntity entity(AiProviderType provider, String apiKeyEncrypted) {
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setName("Test");
        entity.setProvider(provider);
        entity.setModel("test-model");
        entity.setApiKeyEncrypted(apiKeyEncrypted);
        return entity;
    }
}
