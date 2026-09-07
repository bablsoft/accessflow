package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Brings every enabled organization's help corpus up to the version this build ships, once the
 * application is ready (AF-902). This is what makes an upgrade re-index itself: the new jar carries a
 * new {@code corpus.jsonl}, so a content-derived {@code corpusVersion} no longer matches what is
 * stored and the first replica to boot re-embeds it.
 *
 * <p>The work is handed to {@link HelpCorpusIndexDispatcher} and this method returns immediately —
 * {@code ApplicationReadyEvent} listeners run on the main thread, and blocking it would delay
 * readiness (and therefore a rolling deployment) by however long an embedding backend takes.
 *
 * <p>An install with the feature off pays nothing: the dispatcher's pass reads
 * {@code findAllByEnabledTrue()}, finds no rows, and makes no embedding call at all.
 */
@Component
@RequiredArgsConstructor
class HelpCorpusStartupIndexer {

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusStartupIndexer.class);

    private final HelpCorpusIndexDispatcher dispatcher;
    private final HelpAgentProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    void indexOnStartup() {
        if (!properties.indexOnStartup()) {
            log.info("Help corpus startup indexing is disabled "
                    + "(accessflow.help-agent.index-on-startup=false)");
            return;
        }
        log.debug("Requesting a help corpus indexing pass for every enabled organization");
        dispatcher.dispatchAll(false);
    }
}
