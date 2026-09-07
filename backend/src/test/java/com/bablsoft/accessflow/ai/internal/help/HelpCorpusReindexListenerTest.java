package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.AiConfigUpdatedEvent;
import com.bablsoft.accessflow.ai.internal.HelpAgentConfigUpdatedEvent;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpCorpusReindexListenerTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID HELP_CONFIG_ID = UUID.randomUUID();
    private static final UUID AI_CONFIG_ID = UUID.randomUUID();

    @Mock HelpCorpusIndexDispatcher dispatcher;
    @Mock HelpAgentConfigRepository repository;

    @InjectMocks HelpCorpusReindexListener listener;

    // --- help agent configuration changes -------------------------------------------------------

    @Test
    void enablingTheAgentTriggersAnUnforcedPass() {
        listener.onHelpAgentConfigUpdated(event(true, AI_CONFIG_ID, true, false));

        // Unforced: nothing is stored yet, so the version compare already says "index me". Forcing
        // would re-embed the whole corpus every time an admin toggled the agent off and on.
        verify(dispatcher).dispatchOne(HELP_CONFIG_ID, false);
    }

    @Test
    void repointingTheBindingForcesAPass() {
        listener.onHelpAgentConfigUpdated(event(true, AI_CONFIG_ID, true, true));

        // The stored vectors may have come from a different embedding model. Mixed vectors do not
        // error; they quietly return nonsense neighbours.
        verify(dispatcher).dispatchOne(HELP_CONFIG_ID, true);
    }

    @Test
    void ignoresAChangeThatLeavesTheAgentOff() {
        listener.onHelpAgentConfigUpdated(event(false, AI_CONFIG_ID, true, true));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void ignoresAChangeWithRetrievalOff() {
        listener.onHelpAgentConfigUpdated(event(true, AI_CONFIG_ID, false, true));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void ignoresAnEnabledButUnboundAgent() {
        listener.onHelpAgentConfigUpdated(event(true, null, true, true));

        verifyNoInteractions(dispatcher);
    }

    // --- AI configuration changes ---------------------------------------------------------------

    @Test
    void changedRagSettingsForceAReindexOfEveryBoundOrganization() {
        var first = row(true, true);
        var second = row(true, true);
        when(repository.findAllByAiConfigId(AI_CONFIG_ID)).thenReturn(List.of(first, second));

        listener.onAiConfigUpdated(aiConfigEvent(true));

        verify(dispatcher).dispatchOne(first.getId(), true);
        verify(dispatcher).dispatchOne(second.getId(), true);
    }

    @Test
    void anUnrelatedAiConfigurationChangeIndexesNothing() {
        listener.onAiConfigUpdated(aiConfigEvent(false));

        // A renamed model or a rotated chat key does not invalidate a single stored vector.
        verify(repository, never()).findAllByAiConfigId(any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void skipsBoundOrganizationsWhoseAgentIsOffOrNotRetrieving() {
        when(repository.findAllByAiConfigId(AI_CONFIG_ID))
                .thenReturn(List.of(row(false, true), row(true, false)));

        listener.onAiConfigUpdated(aiConfigEvent(true));

        verify(dispatcher, never()).dispatchOne(any(), anyBoolean());
    }

    private static HelpAgentConfigUpdatedEvent event(boolean enabled, UUID aiConfigId,
                                                     boolean retrievalEnabled,
                                                     boolean bindingChanged) {
        return new HelpAgentConfigUpdatedEvent(ORG_ID, HELP_CONFIG_ID, enabled, aiConfigId,
                retrievalEnabled, bindingChanged);
    }

    private static AiConfigUpdatedEvent aiConfigEvent(boolean ragChanged) {
        return new AiConfigUpdatedEvent(AI_CONFIG_ID, AiProviderType.OPENAI, AiProviderType.OPENAI,
                "gpt-4o", "gpt-4o", false, false, ragChanged, false);
    }

    private static HelpAgentConfigEntity row(boolean enabled, boolean retrievalEnabled) {
        var entity = new HelpAgentConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG_ID);
        entity.setAiConfigId(AI_CONFIG_ID);
        entity.setEnabled(enabled);
        entity.setRetrievalEnabled(retrievalEnabled);
        return entity;
    }
}
