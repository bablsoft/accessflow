package com.bablsoft.accessflow.ai.internal.help;

/**
 * One documentation chunk retrieved for a help question, with the metadata a citation needs.
 *
 * <p>This is why {@link HelpCorpusRetriever} is a sibling of {@code DefaultRagRetriever} rather than a
 * reuse of it: that retriever joins chunk texts into a single prompt string and discards every
 * per-chunk field, which is exactly right for injecting a {@code {{rag_context}}} block and useless
 * for resolving a {@code [n]} citation back to a title and a URL.
 *
 * @param chunkId content-hash id of the chunk in {@code corpus.jsonl}
 * @param title   heading of the documentation section
 * @param section documentation area label (Guides, Connectors, Reference, …)
 * @param anchor  heading anchor within the page, or empty
 * @param url     public documentation URL of the section
 * @param text    the chunk body
 * @param score   similarity score reported by the vector store, or {@code null} when it reports none
 */
public record RetrievedChunk(
        String chunkId,
        String title,
        String section,
        String anchor,
        String url,
        String text,
        Double score) {
}
