package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HelpCorpusStartupIndexerTest {

    /** Runs the submitted task inline, so the assertions see the pass the executor would have run. */
    private static final Executor DIRECT = Runnable::run;

    @Mock HelpCorpusIndexDispatcher dispatcher;
    @Mock HelpCorpusRemoteRefresher remoteRefresher;

    @Test
    void requestsAnUnforcedPassOnceTheApplicationIsReady() {
        indexer(true, DIRECT).indexOnStartup();

        // Unforced: the content-derived version compare decides what actually needs re-embedding, so
        // a restart on an unchanged corpus makes no embedding call at all.
        verify(dispatcher).dispatchAll(false);
    }

    @Test
    void refreshesTheCorpusBeforeIndexingIt() {
        indexer(true, DIRECT).indexOnStartup();

        // Order is the whole contract: a pass that ran first would index the corpus the refresh was
        // about to replace, and only correct itself on the next restart.
        InOrder order = inOrder(remoteRefresher, dispatcher);
        order.verify(remoteRefresher).refresh();
        order.verify(dispatcher).dispatchAll(false);
    }

    @Test
    void stillRefreshesWhenStartupIndexingIsTurnedOff() {
        indexer(false, DIRECT).indexOnStartup();

        // Turning indexing off is about not embedding on every restart. The admin re-index button
        // should still find the corpus the operator asked to keep current.
        verify(remoteRefresher).refresh();
        verify(dispatcher, never()).dispatchAll(anyBoolean());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void doesNothingWhenTheExecutorIsAlreadyShuttingDown() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("shutting down");
        };

        indexer(true, rejecting).indexOnStartup();

        verifyNoInteractions(remoteRefresher);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void keepsTheReadinessThreadFreeOfBothTheRefreshAndThePass() {
        // Neither an embedding backend nor the refresh's two HTTP timeouts may delay readiness, so
        // nothing at all runs on the caller's thread.
        indexer(true, task -> { }).indexOnStartup();

        verifyNoInteractions(remoteRefresher);
        verifyNoInteractions(dispatcher);
    }

    private HelpCorpusStartupIndexer indexer(boolean indexOnStartup, Executor executor) {
        return new HelpCorpusStartupIndexer(dispatcher, remoteRefresher,
                new HelpAgentProperties(indexOnStartup, 64, null), executor);
    }
}
