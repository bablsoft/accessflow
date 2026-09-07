package com.bablsoft.accessflow.ai.internal.help;

/**
 * What one pass of {@link HelpCorpusIndexer} did to one {@code help_agent_config} row. Every value
 * except {@link #INDEXED} and {@link #FAILED} means no embedding call was made — which is what makes
 * "startup on an install with the feature off costs nothing" checkable rather than merely intended.
 */
public enum HelpIndexOutcome {

    /** The corpus was embedded and stored, and the row's ingestion state was stamped. */
    INDEXED,

    /** The agent is switched off for this organization. */
    SKIPPED_DISABLED,

    /**
     * The row is gone — deleted between the work list being read and the pass reaching it, or an
     * organization removed mid-pass. Distinct from {@link #SKIPPED_DISABLED}: nothing was switched
     * off, there is simply nothing left to index.
     */
    SKIPPED_MISSING,

    /**
     * The agent is on but has no bound {@code ai_config} — the inert state left behind by deleting the
     * bound configuration ({@code ON DELETE SET NULL}). There is no model to embed with.
     */
    SKIPPED_UNBOUND,

    /**
     * Documentation retrieval is off. A supported steady state, not a fault: the agent answers from
     * the generated quick-reference block, which is the only mode an install whose embedding provider
     * cannot embed has at all.
     */
    SKIPPED_RETRIEVAL_OFF,

    /** This organization already has exactly this corpus version stored. The common case. */
    SKIPPED_UP_TO_DATE,

    /** The store is PGVECTOR but pgvector is disabled or its extension is missing on this deployment. */
    SKIPPED_PGVECTOR_UNAVAILABLE,

    /**
     * Ingestion was attempted and did not complete. {@code index_error} carries the reason, as a
     * message key resolved against the reader's locale.
     *
     * <p>What happens to {@code indexed_corpus_version} depends on how far the pass got. A failure
     * before the store was touched leaves it alone — the stored corpus is still the intact one that
     * string names. A failure after the delete clears it, because the scope is now empty or
     * half-written: leaving a forced pass's already-matching version in place would mark that as up to
     * date and make every later unforced pass skip it.
     */
    FAILED
}
