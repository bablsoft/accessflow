package com.bablsoft.accessflow.ai.internal.help;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.UUID;

/**
 * The metadata contract for help-corpus chunks in the shared {@code vector_store}, and the filter that
 * scopes every read and delete to one organization's help configuration.
 *
 * <p><strong>There is deliberately no {@code ai_config_id} key here.</strong> That is the whole
 * isolation mechanism between this corpus and the AF-336 per-{@code ai_config} knowledge base:
 * {@code DefaultRagRetriever} filters on {@code ai_config_id == '<uuid>'}, a missing key is SQL
 * {@code NULL}, and {@code NULL = 'x'} never matches — so help chunks are invisible to the knowledge
 * base retriever without touching a line of it. Adding the key here would silently merge two corpora
 * that must never mix. The reverse direction holds by the same argument: knowledge chunks carry no
 * {@code corpus} key, so {@link #scopeFilter} never sees them.
 */
final class HelpCorpusMetadata {

    /** Discriminator value distinguishing help chunks from AF-336 knowledge chunks in the same store. */
    static final String CORPUS_HELP = "help";

    static final String KEY_CORPUS = "corpus";
    static final String KEY_HELP_CONFIG_ID = "help_config_id";
    static final String KEY_ORGANIZATION_ID = "organization_id";
    static final String KEY_CORPUS_VERSION = "corpus_version";
    static final String KEY_CHUNK_ID = "chunk_id";
    static final String KEY_TITLE = "title";
    static final String KEY_SECTION = "section";
    static final String KEY_ANCHOR = "anchor";
    static final String KEY_URL = "url";

    private HelpCorpusMetadata() {
    }

    /**
     * {@code corpus == 'help' && help_config_id == '<uuid>'} as a typed expression rather than the
     * string DSL, so it never goes through the ANTLR text parser and cannot be malformed by
     * concatenation. Both {@code VectorStore.delete} and {@code SearchRequest} take this overload.
     */
    static Filter.Expression scopeFilter(UUID helpAgentConfigId) {
        var b = new FilterExpressionBuilder();
        return b.and(
                b.eq(KEY_CORPUS, CORPUS_HELP),
                b.eq(KEY_HELP_CONFIG_ID, helpAgentConfigId.toString())).build();
    }
}
