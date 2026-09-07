package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The orientation block substituted for retrieved documentation whenever retrieval cannot be trusted
 * (AF-903) — the mode that lets the help agent be switched on before RAG is configured, and the only
 * mode an install whose embedding provider cannot embed at all ever has (epic AF-899 decision 9).
 *
 * <p>The text is {@code help-corpus/quick-reference.txt}, generated with the corpus (AF-900) and
 * verified with it, so it always describes the version actually running. It is roughly 3,000 tokens
 * of product orientation: the query lifecycle, the rules that never bend, and where things are in the
 * app. An answer from it is a real answer — it simply cannot cite a section.
 *
 * <p>{@link #usable(HelpAgentConfigEntity)} is the other half: retrieval is only worth attempting
 * when this organization's stored vectors are the ones the running build ships. A row that has never
 * been indexed, is still on a previous corpus version, or recorded an ingestion failure would return
 * chunks from documentation this install no longer has — which is worse than no citation, because it
 * reads as authoritative.
 */
@Component
@RequiredArgsConstructor
public class HelpQuickReference {

    private final HelpCorpusBundle bundle;

    /**
     * Whether this organization's help index matches the bundled corpus and can be searched. False
     * covers every degraded path — retrieval switched off, the bundle unreadable, never indexed, a
     * stale corpus version, or a recorded ingestion error.
     */
    public boolean usable(HelpAgentConfigEntity config) {
        return config.isRetrievalEnabled()
                && bundle.available()
                && config.getIndexError() == null
                && bundle.corpusVersion().equals(config.getIndexedCorpusVersion());
    }

    /** The orientation block, or {@code null} when the bundle itself could not be loaded. */
    public String text() {
        return bundle.available() ? bundle.quickReference() : null;
    }
}
