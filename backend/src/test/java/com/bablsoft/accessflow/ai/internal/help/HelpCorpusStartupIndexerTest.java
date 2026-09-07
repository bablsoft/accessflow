package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.config.HelpAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HelpCorpusStartupIndexerTest {

    @Mock HelpCorpusIndexDispatcher dispatcher;

    @Test
    void requestsAnUnforcedPassOnceTheApplicationIsReady() {
        new HelpCorpusStartupIndexer(dispatcher, new HelpAgentProperties(true, 64, null))
                .indexOnStartup();

        // Unforced: the content-derived version compare decides what actually needs re-embedding, so
        // a restart on an unchanged corpus makes no embedding call at all.
        verify(dispatcher).dispatchAll(false);
    }

    @Test
    void doesNothingWhenStartupIndexingIsTurnedOff() {
        new HelpCorpusStartupIndexer(dispatcher, new HelpAgentProperties(false, 64, null))
                .indexOnStartup();

        verify(dispatcher, never()).dispatchAll(anyBoolean());
        verifyNoInteractions(dispatcher);
    }
}
