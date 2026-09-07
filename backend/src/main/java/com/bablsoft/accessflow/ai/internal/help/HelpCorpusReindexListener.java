package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.AiConfigUpdatedEvent;
import com.bablsoft.accessflow.ai.internal.HelpAgentConfigUpdatedEvent;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Re-indexes the help corpus when a configuration change invalidates what is stored (AF-902), so an
 * admin never has to know that turning the agent on is also an ingestion trigger.
 *
 * <p>Both listeners are {@code @ApplicationModuleListener}, i.e. after commit — the events are
 * published inside the write transaction, and reading the row before that commits would race a
 * rollback.
 *
 * <p>The distinction that matters is <em>when a re-index must be forced</em>. Enabling the agent is
 * not forced: the version compare already says "nothing stored", and forcing would re-embed a corpus
 * an admin merely toggled off and on. Changing which {@code ai_config} the agent uses, or changing
 * that configuration's RAG settings, <strong>is</strong> forced — the stored vectors may have been
 * produced by a different embedding model, or live in a store that is no longer the one being read.
 * Vectors from two models in one scope do not error; they quietly return nonsense neighbours.
 */
@Component
@RequiredArgsConstructor
class HelpCorpusReindexListener {

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusReindexListener.class);

    private final HelpCorpusIndexDispatcher dispatcher;
    private final HelpAgentConfigRepository helpAgentConfigRepository;

    @ApplicationModuleListener
    void onHelpAgentConfigUpdated(HelpAgentConfigUpdatedEvent event) {
        if (!event.enabled() || !event.retrievalEnabled() || event.aiConfigId() == null) {
            return;
        }
        log.debug("Help agent configuration changed for organization {}; requesting an index pass "
                + "(force={})", event.organizationId(), event.bindingChanged());
        dispatcher.dispatchOne(event.helpAgentConfigId(), event.bindingChanged());
    }

    @ApplicationModuleListener
    void onAiConfigUpdated(AiConfigUpdatedEvent event) {
        if (!event.ragChanged()) {
            return;
        }
        for (var row : helpAgentConfigRepository.findAllByAiConfigId(event.aiConfigId())) {
            if (!row.isEnabled() || !row.isRetrievalEnabled()) {
                continue;
            }
            log.info("RAG settings changed on ai_config {}; forcing a help corpus re-index for "
                    + "organization {}", event.aiConfigId(), row.getOrganizationId());
            dispatcher.dispatchOne(row.getId(), true);
        }
    }
}
