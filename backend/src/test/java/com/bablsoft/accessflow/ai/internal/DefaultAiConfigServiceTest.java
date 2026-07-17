package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigEndpointRequiredException;
import com.bablsoft.accessflow.ai.api.AiConfigInUseException;
import com.bablsoft.accessflow.ai.api.AiConfigInvalidPromptException;
import com.bablsoft.accessflow.ai.api.AiConfigNameAlreadyExistsException;
import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.AiConfigRagInvalidException;
import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.AiConfigModelCommand;
import com.bablsoft.accessflow.ai.api.AiConfigOrchestrationInvalidException;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigModelEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigModelRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RagStoreType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.VotingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
    @Mock AiConfigModelRepository modelRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SystemPromptRenderer promptRenderer;
    @Spy ObjectMapper objectMapper = JsonMapper.builder().build();
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

    // --- Provider fallback pool (AF-458) ---

    @Test
    void createPersistsFallbackPriority() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Fallback")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("Fallback", AiProviderType.OLLAMA,
                "llama3.1:70b", null, null, null, null, null, null, null, null, 0);
        var view = service.create(orgId, cmd);

        assertThat(view.fallbackPriority()).isEqualTo(0);
    }

    @Test
    void createNormalizesNegativeFallbackPriorityToNull() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "NotFallback")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("NotFallback", AiProviderType.OLLAMA,
                "llama3.1:70b", null, null, null, null, null, null, null, null, -1);
        var view = service.create(orgId, cmd);

        assertThat(view.fallbackPriority()).isNull();
    }

    @Test
    void updateSetsFallbackPriority() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null,
                null, null, null, 5);
        var view = service.update(configId, orgId, cmd);

        assertThat(view.fallbackPriority()).isEqualTo(5);
        assertThat(entity.getFallbackPriority()).isEqualTo(5);
    }

    @Test
    void updateWithNegativeFallbackPriorityClearsIt() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        entity.setFallbackPriority(3);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null,
                null, null, null, -1);
        var view = service.update(configId, orgId, cmd);

        assertThat(view.fallbackPriority()).isNull();
        assertThat(entity.getFallbackPriority()).isNull();
    }

    @Test
    void updateWithNullFallbackPriorityLeavesItUnchanged() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        entity.setFallbackPriority(2);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, "gpt-4o", null, null, null, null, null,
                null, null, null);
        service.update(configId, orgId, cmd);

        assertThat(entity.getFallbackPriority()).isEqualTo(2);
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

    @Test
    void createWithRagEnabledPersistsRagAndEmbeddingFields() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "RagCfg")).thenReturn(false);
        when(encryptionService.encrypt("ek")).thenReturn("ENC(ek)");
        var captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateAiConfigCommand("RagCfg", AiProviderType.ANTHROPIC, "model", null, null,
                null, null, null, null, null, null,
                true, RagStoreType.PGVECTOR, 5, 0.6, null, null, null,
                AiProviderType.OPENAI, "text-embedding-3-small", null, "ek");
        var view = service.create(orgId, cmd);

        var saved = captor.getValue();
        assertThat(saved.isRagEnabled()).isTrue();
        assertThat(saved.getRagStoreType()).isEqualTo(RagStoreType.PGVECTOR);
        assertThat(saved.getRagTopK()).isEqualTo(5);
        assertThat(saved.getRagSimilarityThreshold()).isEqualTo(0.6);
        assertThat(saved.getEmbeddingProvider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(saved.getEmbeddingApiKeyEncrypted()).isEqualTo("ENC(ek)");
        assertThat(view.ragEnabled()).isTrue();
        assertThat(view.embeddingApiKeyMasked()).isTrue();
    }

    @Test
    void createRagWithoutStoreTypeThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "R")).thenReturn(false);

        var cmd = ragCommand(null, AiProviderType.OPENAI, "m", null, null);
        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigRagInvalidException.class)
                .extracting("messageKey").isEqualTo("error.ai_config.rag.store_type_required");
    }

    @Test
    void createRagWithAnthropicEmbeddingProviderThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "R")).thenReturn(false);

        var cmd = ragCommand(RagStoreType.PGVECTOR, AiProviderType.ANTHROPIC, "m", null, null);
        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigRagInvalidException.class)
                .extracting("messageKey").isEqualTo("error.ai_config.rag.embedding_provider_invalid");
    }

    @Test
    void createRagWithoutEmbeddingModelThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "R")).thenReturn(false);

        var cmd = ragCommand(RagStoreType.PGVECTOR, AiProviderType.OPENAI, null, null, null);
        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigRagInvalidException.class)
                .extracting("messageKey").isEqualTo("error.ai_config.rag.embedding_model_required");
    }

    @Test
    void createRagQdrantWithoutEndpointThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "R")).thenReturn(false);

        var cmd = ragCommand(RagStoreType.QDRANT, AiProviderType.OPENAI, "m", null, null);
        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigRagInvalidException.class)
                .extracting("messageKey").isEqualTo("error.ai_config.rag.external_endpoint_required");
    }

    @Test
    void createRagQdrantWithoutCollectionThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "R")).thenReturn(false);

        var cmd = ragCommand(RagStoreType.QDRANT, AiProviderType.OPENAI, "m", "http://q:6334", null);
        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigRagInvalidException.class)
                .extracting("messageKey").isEqualTo("error.ai_config.rag.external_collection_required");
    }

    @Test
    void updateEnablingRagPublishesEventWithRagChanged() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, null, null, null,
                true, RagStoreType.PGVECTOR, 4, 0.5, null, null, null,
                AiProviderType.OPENAI, "text-embedding-3-small", null, null);
        service.update(configId, orgId, cmd);

        var captor = ArgumentCaptor.forClass(AiConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().ragChanged()).isTrue();
    }

    @Test
    void updateRagQdrantEncryptsKeysAndAppliesConnection() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.encrypt("rag-key")).thenReturn("ENC(rag)");
        when(encryptionService.encrypt("embed-key")).thenReturn("ENC(embed)");
        var captor = ArgumentCaptor.forClass(AiConfigEntity.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, null, null, null,
                true, RagStoreType.QDRANT, 9, 0.8, "http://qdrant:6334", "kb", "rag-key",
                AiProviderType.OPENAI, "text-embedding-3-small", "http://embed:1234", "embed-key");
        service.update(configId, orgId, cmd);

        var saved = captor.getValue();
        assertThat(saved.getRagStoreType()).isEqualTo(RagStoreType.QDRANT);
        assertThat(saved.getRagTopK()).isEqualTo(9);
        assertThat(saved.getRagSimilarityThreshold()).isEqualTo(0.8);
        assertThat(saved.getRagEndpoint()).isEqualTo("http://qdrant:6334");
        assertThat(saved.getRagCollection()).isEqualTo("kb");
        assertThat(saved.getRagApiKeyEncrypted()).isEqualTo("ENC(rag)");
        assertThat(saved.getEmbeddingEndpoint()).isEqualTo("http://embed:1234");
        assertThat(saved.getEmbeddingApiKeyEncrypted()).isEqualTo("ENC(embed)");
    }

    @Test
    void updateRagMaskedKeysLeaveCiphertextUnchanged() {
        var entity = build(configId, orgId, "Prod", AiProviderType.OPENAI);
        entity.setRagEnabled(true);
        entity.setRagStoreType(RagStoreType.PGVECTOR);
        entity.setEmbeddingProvider(AiProviderType.OPENAI);
        entity.setEmbeddingModel("m");
        entity.setRagApiKeyEncrypted("ENC(old-rag)");
        entity.setEmbeddingApiKeyEncrypted("ENC(old-embed)");
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, UpdateAiConfigCommand.MASKED_API_KEY,
                null, null, null, UpdateAiConfigCommand.MASKED_API_KEY);
        service.update(configId, orgId, cmd);

        assertThat(entity.getRagApiKeyEncrypted()).isEqualTo("ENC(old-rag)");
        assertThat(entity.getEmbeddingApiKeyEncrypted()).isEqualTo("ENC(old-embed)");
    }

    // --- AF-450: orchestration + guardrails ---

    @Test
    void createWithOrchestrationPersistsScalarsMemberAndGuardrails() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Ensemble")).thenReturn(false);
        when(encryptionService.encrypt("member-key")).thenReturn("ENC(member)");
        var configCaptor = ArgumentCaptor.forClass(AiConfigEntity.class);
        when(repository.save(configCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        var memberCaptor = ArgumentCaptor.forClass(AiConfigModelEntity.class);
        when(modelRepository.save(memberCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var member = new AiConfigModelCommand(null, AiProviderType.OLLAMA, "llama3", null, "member-key",
                2.0, true);
        var cmd = orchestrationCreateCommand("Ensemble", true, VotingStrategy.MAJORITY, 1.5,
                List.of("ignore previous", "drop\\s+table"), List.of(member));

        var view = service.create(orgId, cmd);

        var savedConfig = configCaptor.getValue();
        assertThat(savedConfig.isOrchestrationEnabled()).isTrue();
        assertThat(savedConfig.getVotingStrategy()).isEqualTo(VotingStrategy.MAJORITY);
        assertThat(savedConfig.getVotingWeight()).isEqualTo(1.5);
        assertThat(savedConfig.getGuardrailPatterns()).contains("ignore previous").contains("drop");
        var savedMember = memberCaptor.getValue();
        assertThat(savedMember.getProvider()).isEqualTo(AiProviderType.OLLAMA);
        assertThat(savedMember.getModel()).isEqualTo("llama3");
        assertThat(savedMember.getWeight()).isEqualTo(2.0);
        assertThat(savedMember.getApiKeyEncrypted()).isEqualTo("ENC(member)");
        assertThat(view.orchestrationEnabled()).isTrue();
        assertThat(view.guardrailPatterns()).containsExactly("ignore previous", "drop\\s+table");
    }

    @Test
    void createWithInvalidGuardrailRegexThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Bad")).thenReturn(false);

        var cmd = orchestrationCreateCommand("Bad", true, VotingStrategy.WEIGHTED_AVERAGE, 1.0,
                List.of("("), List.of());

        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigOrchestrationInvalidException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createWithOpenAiCompatibleMemberMissingEndpointThrows() {
        when(repository.existsByOrganizationIdAndNameIgnoreCase(orgId, "Bad")).thenReturn(false);
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var member = new AiConfigModelCommand(null, AiProviderType.OPENAI_COMPATIBLE, "m", null, null,
                1.0, true);
        var cmd = orchestrationCreateCommand("Bad", true, VotingStrategy.WEIGHTED_AVERAGE, 1.0,
                List.of(), List.of(member));

        assertThatThrownBy(() -> service.create(orgId, cmd))
                .isInstanceOf(AiConfigOrchestrationInvalidException.class);
    }

    @Test
    void updateChangingVotingStrategyPublishesEventWithOrchestrationChanged() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, VotingStrategy.MAX_RISK, null, null, null, null);
        service.update(configId, orgId, cmd);

        var event = ArgumentCaptor.forClass(AiConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().orchestrationChanged()).isTrue();
    }

    @Test
    void updateMemberWithMaskedKeyPreservesCiphertextAndDeletesRemoved() {
        var entity = build(configId, orgId, "Prod", AiProviderType.ANTHROPIC);
        var existing = new AiConfigModelEntity();
        existing.setId(UUID.randomUUID());
        existing.setAiConfigId(configId);
        existing.setProvider(AiProviderType.OLLAMA);
        existing.setModel("llama3");
        existing.setApiKeyEncrypted("ENC(old)");
        var stale = new AiConfigModelEntity();
        stale.setId(UUID.randomUUID());
        stale.setAiConfigId(configId);
        stale.setProvider(AiProviderType.OPENAI);
        stale.setModel("gone");
        when(repository.findByIdAndOrganizationId(configId, orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(AiConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(modelRepository.findByAiConfigIdOrderBySortOrderAsc(configId))
                .thenReturn(List.of(existing, stale));
        when(modelRepository.save(any(AiConfigModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceLookupService.countsByAiConfigIds(Set.of(configId))).thenReturn(Map.of(configId, 0));

        var keep = new AiConfigModelCommand(existing.getId(), AiProviderType.OLLAMA, "llama3", null,
                UpdateAiConfigCommand.MASKED_API_KEY, 3.0, true);
        var cmd = new UpdateAiConfigCommand(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                true, null, null, null, List.of(keep), null);
        service.update(configId, orgId, cmd);

        assertThat(existing.getApiKeyEncrypted()).isEqualTo("ENC(old)"); // masked → preserved
        assertThat(existing.getWeight()).isEqualTo(3.0);
        verify(modelRepository).delete(stale); // removed member deleted
    }

    private CreateAiConfigCommand orchestrationCreateCommand(String name, boolean enabled,
            VotingStrategy strategy, Double weight, List<String> guardrails,
            List<AiConfigModelCommand> models) {
        return new CreateAiConfigCommand(name, AiProviderType.ANTHROPIC, "claude-sonnet-4", null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                enabled, strategy, weight, guardrails, models, null);
    }

    private CreateAiConfigCommand ragCommand(RagStoreType storeType, AiProviderType embeddingProvider,
                                             String embeddingModel, String ragEndpoint, String ragCollection) {
        return new CreateAiConfigCommand("R", AiProviderType.ANTHROPIC, "model", null, null,
                null, null, null, null, null, null,
                true, storeType, 4, 0.5, ragEndpoint, ragCollection, null,
                embeddingProvider, embeddingModel, null, null);
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
