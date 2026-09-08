package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpQuickReferenceTest {

    private static final String CORPUS_VERSION = "b3afb123fe5b";

    @Mock HelpCorpusBundle bundle;

    private HelpQuickReference quickReference() {
        return new HelpQuickReference(bundle);
    }

    @Test
    void indexIsUsableOnlyWhenItHoldsTheBundledCorpusVersion() {
        available();

        assertThat(quickReference().usable(config(c -> c.setIndexedCorpusVersion(CORPUS_VERSION))))
                .isTrue();
    }

    @Test
    void retrievalSwitchedOffIsNotUsable() {
        assertThat(quickReference().usable(config(c -> {
            c.setIndexedCorpusVersion(CORPUS_VERSION);
            c.setRetrievalEnabled(false);
        }))).isFalse();
    }

    @Test
    void neverIndexedIsNotUsable() {
        available();

        assertThat(quickReference().usable(config(c -> { }))).isFalse();
    }

    @Test
    void staleCorpusVersionIsNotUsable() {
        available();

        assertThat(quickReference().usable(config(c -> c.setIndexedCorpusVersion("0000deadbeef"))))
                .isFalse();
    }

    @Test
    void recordedIngestionErrorIsNotUsable() {
        lenient().when(bundle.available()).thenReturn(true);
        lenient().when(bundle.corpusVersion()).thenReturn(CORPUS_VERSION);

        assertThat(quickReference().usable(config(c -> {
            c.setIndexedCorpusVersion(CORPUS_VERSION);
            c.setIndexError("error.help_agent.index.failed");
        }))).isFalse();
    }

    @Test
    void unreadableBundleIsNotUsableAndHasNoText() {
        when(bundle.available()).thenReturn(false);

        assertThat(quickReference().usable(config(c -> c.setIndexedCorpusVersion(CORPUS_VERSION))))
                .isFalse();
        assertThat(quickReference().text()).isNull();
    }

    @Test
    void textIsTheBundledQuickReferenceBlock() {
        when(bundle.available()).thenReturn(true);
        when(bundle.quickReference()).thenReturn("AccessFlow — quick reference");

        assertThat(quickReference().text()).isEqualTo("AccessFlow — quick reference");
    }

    private void available() {
        when(bundle.available()).thenReturn(true);
        when(bundle.corpusVersion()).thenReturn(CORPUS_VERSION);
    }

    private static HelpAgentConfigEntity config(java.util.function.Consumer<HelpAgentConfigEntity> customizer) {
        var config = new HelpAgentConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(UUID.randomUUID());
        config.setEnabled(true);
        config.setAiConfigId(UUID.randomUUID());
        customizer.accept(config);
        return config;
    }
}
