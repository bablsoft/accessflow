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
 * verified with it, so it always describes the version actually running. It is roughly 5,900 tokens
 * of product orientation: the query lifecycle, the rules that never bend, the sidebar menu with each
 * destination's label, menu path and revealing permissions, the exact control labels on the main task
 * flows (#925), and the screens with no menu entry. An answer from it is a real answer — it simply
 * cannot cite a section.
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
        return reason(config) == Reason.USABLE;
    }

    /**
     * Which of the five degraded states this organization is in, or {@link Reason#USABLE}.
     *
     * <p>{@link #usable(HelpAgentConfigEntity)} collapses all five into one boolean, which is all the
     * answer path needs but leaves an operator with no way to tell "nobody has indexed yet" from "the
     * index recorded a failure" — the difference between waiting and acting. The caller logs this.
     */
    public Reason reason(HelpAgentConfigEntity config) {
        if (!config.isRetrievalEnabled()) {
            return Reason.RETRIEVAL_DISABLED;
        }
        if (!bundle.available()) {
            return Reason.BUNDLE_UNAVAILABLE;
        }
        if (config.getIndexError() != null) {
            return Reason.INDEX_ERROR;
        }
        if (config.getIndexedCorpusVersion() == null) {
            return Reason.NEVER_INDEXED;
        }
        if (!bundle.corpusVersion().equals(config.getIndexedCorpusVersion())) {
            return Reason.STALE_CORPUS;
        }
        return Reason.USABLE;
    }

    /** Why retrieval is or is not available for an organization. Operator diagnostics only. */
    public enum Reason {
        USABLE,
        RETRIEVAL_DISABLED,
        BUNDLE_UNAVAILABLE,
        INDEX_ERROR,
        NEVER_INDEXED,
        STALE_CORPUS
    }

    /** The orientation block, or {@code null} when the bundle itself could not be loaded. */
    public String text() {
        return bundle.available() ? bundle.quickReference() : null;
    }
}
