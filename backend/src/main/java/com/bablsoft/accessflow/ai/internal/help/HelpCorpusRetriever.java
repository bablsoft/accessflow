package com.bablsoft.accessflow.ai.internal.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retrieves the most similar bundled-documentation chunks for a help question (AF-902).
 *
 * <p>A deliberate <em>sibling</em> of {@code DefaultRagRetriever}, not a modification of it: that
 * class is the AF-336 knowledge-base path every existing customer depends on, and it stays untouched.
 * Two things differ here — the filter is {@code corpus == 'help' && help_config_id == '<uuid>'}
 * instead of {@code ai_config_id}, and the result is a list of {@link RetrievedChunk} rather than a
 * joined string, because citations need per-chunk metadata.
 *
 * <p>The never-throw contract is the same: any failure (store down, embedding error) is logged at
 * {@code WARN} and degrades to an empty list. A help answer without citations is still an answer; an
 * exception here would turn a documentation question into a 500.
 *
 * <p>{@code topK} and {@code similarityThreshold} come from the {@code help_agent_config} row (6 /
 * 0.4), not from the bound {@code ai_config} (4 / 0.5) — help retrieval is tuned for recall over a
 * fixed corpus, knowledge-base retrieval for precision over customer text.
 */
public class HelpCorpusRetriever {

    private static final Logger log = LoggerFactory.getLogger(HelpCorpusRetriever.class);

    private final VectorStore vectorStore;
    private final UUID helpAgentConfigId;
    private final int topK;
    private final double similarityThreshold;

    public HelpCorpusRetriever(VectorStore vectorStore, UUID helpAgentConfigId, int topK,
                               double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.helpAgentConfigId = helpAgentConfigId;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    /** The best-matching chunks for {@code query}, most similar first; empty when nothing matches. */
    public List<RetrievedChunk> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            var request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(HelpCorpusMetadata.scopeFilter(helpAgentConfigId))
                    .build();
            var docs = vectorStore.similaritySearch(request);
            if (docs == null || docs.isEmpty()) {
                return List.of();
            }
            return docs.stream().map(HelpCorpusRetriever::toChunk).toList();
        } catch (RuntimeException e) {
            log.warn("Help corpus retrieval failed for help_agent_config={}: {}", helpAgentConfigId,
                    e.getMessage());
            return List.of();
        }
    }

    private static RetrievedChunk toChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RetrievedChunk(
                string(metadata, HelpCorpusMetadata.KEY_CHUNK_ID),
                string(metadata, HelpCorpusMetadata.KEY_TITLE),
                string(metadata, HelpCorpusMetadata.KEY_SECTION),
                string(metadata, HelpCorpusMetadata.KEY_ANCHOR),
                string(metadata, HelpCorpusMetadata.KEY_URL),
                document.getText(),
                document.getScore());
    }

    private static String string(Map<String, Object> metadata, String key) {
        var value = metadata.get(key);
        return value == null ? null : value.toString();
    }
}
