package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Brings every enabled organization's help corpus up to the version this replica holds, once the
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
 *
 * <p>The optional remote corpus refresh (AF-907) runs <em>before</em> the pass and on the same
 * off-startup thread, so the indexing that follows already sees whichever corpus won: a newer
 * published corpus then re-indexes by exactly the mechanism a bundled version change does, with no
 * second code path. It runs whether or not startup indexing is on — turning indexing off is about not
 * embedding on every restart, and the admin re-index button should still find the current corpus —
 * and it is a no-op that touches no network unless an operator switched it on. Its two HTTP timeouts
 * are the other reason it cannot sit on the readiness thread.
 */
@Component
class HelpCorpusStartupIndexer {

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusStartupIndexer.class);

    private final HelpCorpusIndexDispatcher dispatcher;
    private final HelpCorpusRemoteRefresher remoteRefresher;
    private final HelpAgentProperties properties;
    private final Executor executor;

    HelpCorpusStartupIndexer(HelpCorpusIndexDispatcher dispatcher,
                             HelpCorpusRemoteRefresher remoteRefresher,
                             HelpAgentProperties properties,
                             @Qualifier("helpCorpusIndexExecutor") Executor executor) {
        this.dispatcher = dispatcher;
        this.remoteRefresher = remoteRefresher;
        this.properties = properties;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexOnStartup() {
        try {
            executor.execute(this::refreshThenIndex);
        } catch (RejectedExecutionException shuttingDown) {
            log.debug("Help corpus startup pass was rejected by the executor (shutting down?)",
                    shuttingDown);
        }
    }

    void refreshThenIndex() {
        remoteRefresher.refresh();
        if (!properties.indexOnStartup()) {
            log.info("Help corpus startup indexing is disabled "
                    + "(accessflow.help-agent.index-on-startup=false)");
            return;
        }
        log.debug("Requesting a help corpus indexing pass for every enabled organization");
        dispatcher.dispatchAll(false);
    }
}
