package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigEndpointRequiredException;
import com.bablsoft.accessflow.ai.api.AiConfigInUseException;
import com.bablsoft.accessflow.ai.api.AiConfigInvalidPromptException;
import com.bablsoft.accessflow.ai.api.AiConfigNameAlreadyExistsException;
import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiConfigServiceTest {

    @Mock AiConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SystemPromptRenderer promptRenderer;
    @InjectMocks DefaultAiConfigService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID otherOrgId = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();

    // A minimal custom template that satisfies the {{sql}} guard.
    private static final String CUSTOM_PROMPT = "Custom rules.\nSQL: {{sql}}\nRespond in: {{language}}.";

    @Test
    void listReturnsViewsWithInUseCounts() {
        var a = build(UUID.randomUUID(), orgId, "AnthropicProd", AiProviderType.ANTHROPIC);
        var b = build(UUID.randomUUID(), orgId, "OpenAI", AiProviderType.OPENAI);
        when(repository.findAllByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(a, b));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(a.getId(), b.getId())))
                .thenReturn(Map.of(a.getId(), 2, b.getId(), 0));

        var views = service.list(orgId);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).inUseCount()).isEqualTo(2);
        assertThat(views.get(1).inUseCount()).isEqualTo(0);
    }

    @Test
    void listEmptyWhenNoRows() {
        when(repository.findAllByOrganizationIdOrderByNameAsc(orgId)).thenReturn(List.of());

        var views = service.list(orgId);

        assertThat(views).isEmpty();
        verify(datasourceLookupService, never()).countsByAiConfigIds(any());
    }

    @Test
    void getReturnsViewWhenInOrg() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 1));

        var view = service.get(configId, orgId);

        assertThat(view.id()).isEqualTo(configId);
        assertThat(view.inUseCount()).isEqualTo(1);
    }

    @Test
    void getThrowsWhenNotInOrg() {
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(configId, orgId))
                .isInstanceOf(AiConfigNotFoundException.class);
    }

    @Test
    void createPersistsAndEncryptsApiKey() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Prod")).thenReturn(false);
        when(encryptionService.encrypt("sk-test")).thenReturn("ENC(sk-test)");
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("  Prod  ", AiProviderType.ANTHROPIC,
                "claude-sonnet-4-20250514", null, "sk-test", null, null, null, null, null, null);
        var view = service.create(orgId, cmd);

        assertThat(view.name()).isEqualTo("Prod");
        assertThat(view.provider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(view.apiKeyMasked()).isTrue();
        assertThat(view.inUseCount()).isZero();
        verify(encryptionService).encrypt("sk-test");
    }

    @Test
    void createWithoutApiKeyDoesNotEncrypt() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Local")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("Local", AiProviderType.OLLAMA,
                "llama3.1:70b", "http://localhost:11434", null, null, null, null, null, null, null);
        var view = service.create(orgId, cmd);

        assertThat(view.apiKeyMasked()).isFalse();
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void createPersistsSystemPromptTemplate() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Prod")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("Prod", AiProviderType.OPENAI,
                "gpt-4o", null, "sk", null, null, null, CUSTOM_PROMPT, null, null);
        when(encryptionService.encrypt("sk")).thenReturn("ENC(sk)");
        var view = service.create(orgId, cmd);

        assertThat(view.systemPromptTemplate()).isEqualTo(CUSTOM_PROMPT);
    }

    @Test
    void createBlankSystemPromptStoredAsNull() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Local")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("Local", AiProviderType.OLLAMA,
                "llama3.1:70b", "http://localhost:11434", null, null, null, null, "   ", null, null);
        var view = service.create(orgId, cmd);

        assertThat(view.systemPromptTemplate()).isNull();
    }

    @Test
    void createWithLangfusePromptDefaultsLabelToProduction() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Prod")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("Prod", AiProviderType.OPENAI,
                "gpt-4o", null, "sk", null, null, null, null, "sql-analysis", "  ");
        when(encryptionService.encrypt("sk")).thenReturn("ENC(sk)");
        var view = service.create(orgId, cmd);

        assertThat(view.langfusePromptName()).isEqualTo("sql-analysis");
        assertThat(view.langfusePromptLabel()).isEqualTo("production");
    }

    @Test
    void updateSettingLangfusePromptNamePublishesEvent() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, null,
                "sql-analysis", "staging");
        var view = service.update(configId, orgId, cmd);

        assertThat(view.langfusePromptName()).isEqualTo("sql-analysis");
        assertThat(view.langfusePromptLabel()).isEqualTo("staging");
        verify(eventPublisher).publishEvent(any(AiConfigUpdatedEvent.class));
    }

    @Test
    void updateClearingLangfusePromptNameClearsLabel() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        entity.setLangfusePromptName("sql-analysis");
        entity.setLangfusePromptLabel("production");
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, null, "", null);
        var view = service.update(configId, orgId, cmd);

        assertThat(view.langfusePromptName()).isNull();
        assertThat(view.langfusePromptLabel()).isNull();
    }

    @Test
    void createWithPromptMissingSqlPlaceholderThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Prod")).thenReturn(false);

        var cmd = new CreateAiConfigCommand("Prod", AiProviderType.OPENAI,
                "gpt-4o", null, null, null, null, null, "No placeholder here", null, null);

        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigInvalidPromptException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createOpenAiCompatibleWithoutEndpointThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Compat")).thenReturn(false);

        var cmd = new CreateAiConfigCommand("Compat", AiProviderType.OPENAI_COMPATIBLE,
                "qwen2.5", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigEndpointRequiredException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createOpenAiCompatibleWithEndpointSucceeds() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Compat")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("Compat", AiProviderType.OPENAI_COMPATIBLE,
                "qwen2.5", "https://api.example.com/v1", null, null, null, null, null, null, null);
        var view = service.create(orgId, cmd);

        assertThat(view.provider()).isEqualTo(AiProviderType.OPENAI_COMPATIBLE);
        assertThat(view.endpoint()).isEqualTo("https://api.example.com/v1");
        assertThat(view.apiKeyMasked()).isFalse();
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void createHuggingFaceWithoutEndpointSucceedsKeyless() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "HF")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // HUGGING_FACE has a built-in router default, so a blank endpoint is allowed (unlike
        // OPENAI_COMPATIBLE), and it is keyless-capable for local TGI.
        var cmd = new CreateAiConfigCommand("HF", AiProviderType.HUGGING_FACE,
                "meta-llama/Llama-3.3-70B-Instruct", null, null, null, null, null, null, null, null);
        var view = service.create(orgId, cmd);

        assertThat(view.provider()).isEqualTo(AiProviderType.HUGGING_FACE);
        assertThat(view.endpoint()).isNull();
        assertThat(view.apiKeyMasked()).isFalse();
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void updateToOpenAiCompatibleWithoutEndpointThrows() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));

        var cmd = new UpdateAiConfigCommand(null, AiProviderType.OPENAI_COMPATIBLE, null, null,
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(configId, orgId, cmd))
                .isInstanceOf(AiConfigEndpointRequiredException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createDuplicateNameThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Prod")).thenReturn(true);

        var cmd = new CreateAiConfigCommand("Prod", AiProviderType.ANTHROPIC,
                "claude-sonnet-4-20250514", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigNameAlreadyExistsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesPartialFieldsAndPublishesEvent() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        entity.setModel("claude-sonnet-4-20250514");
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, AiProviderType.OPENAI, "gpt-4o", null, null,
                null, null, null, null, null, null);
        var view = service.update(configId, orgId, cmd);

        assertThat(view.provider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(view.model()).isEqualTo("gpt-4o");
        var event = ArgumentCaptor.forClass(AiConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().aiConfigId()).isEqualTo(configId);
        assertThat(event.getValue().oldProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(event.getValue().newProvider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(event.getValue().promptChanged()).isFalse();
    }

    @Test
    void updateSystemPromptPersistsAndPublishesPromptChangedEvent() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, CUSTOM_PROMPT, null, null);
        var view = service.update(configId, orgId, cmd);

        assertThat(view.systemPromptTemplate()).isEqualTo(CUSTOM_PROMPT);
        var event = ArgumentCaptor.forClass(AiConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().promptChanged()).isTrue();
        assertThat(event.getValue().apiKeyChanged()).isFalse();
    }

    @Test
    void updateBlankSystemPromptResetsToDefaultAndPublishesEvent() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        entity.setSystemPromptTemplate(CUSTOM_PROMPT);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, "", null, null);
        var view = service.update(configId, orgId, cmd);

        assertThat(view.systemPromptTemplate()).isNull();
        var event = ArgumentCaptor.forClass(AiConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().promptChanged()).isTrue();
    }

    @Test
    void updateNullSystemPromptLeavesUnchangedAndPublishesNoEvent() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        entity.setSystemPromptTemplate(CUSTOM_PROMPT);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        // Only the name changes — prompt arg is null (= unchanged), nothing connectivity-relevant.
        var cmd = new UpdateAiConfigCommand("Prod-renamed", null, null, null, null, null, null, null, null, null, null);
        var view = service.update(configId, orgId, cmd);

        assertThat(view.systemPromptTemplate()).isEqualTo(CUSTOM_PROMPT);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateWithPromptMissingSqlPlaceholderThrows() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null,
                "Analyze this but forgot the placeholder", null, null);

        assertThatThrownBy(() -> service.update(configId, orgId, cmd))
                .isInstanceOf(AiConfigInvalidPromptException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateWithMaskedApiKeyPreservesCiphertext() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        entity.setApiKeyEncrypted("ENC(existing)");
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null,
                UpdateAiConfigCommand.MASKED_API_KEY, null, null, null, null, null, null);
        service.update(configId, orgId, cmd);

        assertThat(entity.getApiKeyEncrypted()).isEqualTo("ENC(existing)");
        verify(encryptionService, never()).encrypt(anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateWithBlankApiKeyClearsCiphertext() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        entity.setApiKeyEncrypted("ENC(existing)");
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, "", null, null, null, null, null, null);
        service.update(configId, orgId, cmd);

        assertThat(entity.getApiKeyEncrypted()).isNull();
        var event = ArgumentCaptor.forClass(AiConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().apiKeyChanged()).isTrue();
    }

    @Test
    void updateRenameConflictThrows() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(eq(orgId), eq("Other"), eq(configId)))
                .thenReturn(true);

        var cmd = new UpdateAiConfigCommand("Other", null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.update(configId, orgId, cmd))
                .isInstanceOf(AiConfigNameAlreadyExistsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateNotFoundThrows() {
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.empty());

        var cmd = new UpdateAiConfigCommand(null, null, "new-model", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.update(configId, orgId, cmd))
                .isInstanceOf(AiConfigNotFoundException.class);
    }

    @Test
    void deleteEmitsDeletedEventWhenUnused() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(datasourceLookupService.findRefsByAiConfigId(configId)).thenReturn(List.of());

        service.delete(configId, orgId);

        verify(repository).delete(entity);
        verify(eventPublisher).publishEvent(new AiConfigDeletedEvent(configId));
    }

    @Test
    void deleteWhenInUseThrows() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        var dsId = UUID.randomUUID();
        when(datasourceLookupService.findRefsByAiConfigId(configId))
                .thenReturn(List.of(new DatasourceRef(dsId, "primary-db")));

        assertThatThrownBy(() -> service.delete(configId, orgId))
                .isInstanceOf(AiConfigInUseException.class)
                .satisfies(ex -> {
                    var inUse = (AiConfigInUseException) ex;
                    assertThat(inUse.boundDatasources()).hasSize(1);
                    assertThat(inUse.boundDatasources().get(0).id()).isEqualTo(dsId);
                    assertThat(inUse.boundDatasources().get(0).name()).isEqualTo("primary-db");
                });
        verify(repository, never()).delete(any(AiConfigEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deleteCrossOrgThrowsNotFound() {
        when(repository.findByIdAndOrganizationId(configId, otherOrgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(configId, otherOrgId))
                .isInstanceOf(AiConfigNotFoundException.class);
    }

    @Test
    void updateApiKeyTriggersEvent() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("sk-new")).thenReturn("ENC(sk-new)");
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId)))
                .thenReturn(Map.of(configId, 0));

        service.update(configId, orgId, new UpdateAiConfigCommand(
                null, null, null, null, "sk-new", null, null, null, null, null, null));

        verify(eventPublisher, times(1)).publishEvent(any(AiConfigUpdatedEvent.class));
    }

    @Test
    void defaultSystemPromptTemplateDelegatesToRenderer() {
        when(promptRenderer.defaultTemplate()).thenReturn(SystemPromptRenderer.DEFAULT_TEMPLATE);

        assertThat(service.defaultSystemPromptTemplate()).isEqualTo(SystemPromptRenderer.DEFAULT_TEMPLATE);
    }

    private static AiConfigEntity build(UUID id, UUID organizationId, String name,
                                        AiProviderType provider) {
        var entity = new AiConfigEntity();
        entity.setId(id);
        entity.setOrganizationId(organizationId);
        entity.setName(name);
        entity.setProvider(provider);
        entity.setModel("model");
        entity.setTimeoutMs(30_000);
        entity.setMaxPromptTokens(8_000);
        entity.setMaxCompletionTokens(2_000);
        return entity;
    }
}
