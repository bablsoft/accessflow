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
        assertThat(quickReference().reason(config(c -> { })))
                .isEqualTo(HelpQuickReference.Reason.NEVER_INDEXED);
    }

    /**
     * The five degraded states {@code usable()} collapses into one boolean, told apart. An operator
     * reading the answer-path log needs "nobody has indexed yet" to look different from "the index
     * recorded a failure" — one is waiting, the other is acting.
     */
    @Test
    void reasonNamesWhichDegradedStateItIs() {
        assertThat(quickReference().reason(config(c -> c.setRetrievalEnabled(false))))
                .isEqualTo(HelpQuickReference.Reason.RETRIEVAL_DISABLED);

        when(bundle.available()).thenReturn(false);
        assertThat(quickReference().reason(config(c -> { })))
                .isEqualTo(HelpQuickReference.Reason.BUNDLE_UNAVAILABLE);

        when(bundle.available()).thenReturn(true);
        assertThat(quickReference().reason(config(c -> c.setIndexError("error.help_agent.rag_not_enabled"))))
                .isEqualTo(HelpQuickReference.Reason.INDEX_ERROR);

        lenient().when(bundle.corpusVersion()).thenReturn(CORPUS_VERSION);
        assertThat(quickReference().reason(config(c -> c.setIndexedCorpusVersion("older"))))
                .isEqualTo(HelpQuickReference.Reason.STALE_CORPUS);
        assertThat(quickReference().reason(config(c -> c.setIndexedCorpusVersion(CORPUS_VERSION))))
                .isEqualTo(HelpQuickReference.Reason.USABLE);
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
        // Lenient: reason() short-circuits before the version comparison for the states that are
        // decided earlier (never indexed, a recorded index error), so not every case reaches it.
        lenient().when(bundle.corpusVersion()).thenReturn(CORPUS_VERSION);
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
