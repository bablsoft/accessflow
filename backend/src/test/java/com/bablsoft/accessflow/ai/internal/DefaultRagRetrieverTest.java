package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRagRetrieverTest {

    private static final UUID AI_CONFIG_ID = UUID.randomUUID();

    @Mock VectorStore vectorStore;

    private DefaultRagRetriever retriever() {
        return new DefaultRagRetriever(vectorStore, AI_CONFIG_ID, 4, 0.5);
    }

    @Test
    void joinsRetrievedChunksWithSeparator() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("first chunk"), new Document("second chunk")));

        var context = retriever().retrieve("SELECT * FROM users");

        assertThat(context).contains("first chunk");
        assertThat(context).contains("second chunk");
        assertThat(context).contains("---");
    }

    @Test
    void returnsNullForNullOrBlankQuery() {
        assertThat(retriever().retrieve(null)).isNull();
        assertThat(retriever().retrieve("   ")).isNull();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void returnsNullWhenNoHits() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertThat(retriever().retrieve("SELECT 1")).isNull();
    }

    @Test
    void returnsNullWhenHitsAreBlank() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("   ")));

        assertThat(retriever().retrieve("SELECT 1")).isNull();
    }

    @Test
    void swallowsRetrievalFailureAndReturnsNull() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("store down"));

        assertThat(retriever().retrieve("SELECT 1")).isNull();
    }

    @Test
    void disabledRetrieverAlwaysReturnsNull() {
        assertThat(RagRetriever.DISABLED.retrieve("anything")).isNull();
    }
}
