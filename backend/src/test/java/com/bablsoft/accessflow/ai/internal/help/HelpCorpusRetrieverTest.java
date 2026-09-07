package com.bablsoft.accessflow.ai.internal.help;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HelpCorpusRetrieverTest {

    private static final UUID HELP_CONFIG_ID = UUID.randomUUID();

    @Mock VectorStore vectorStore;

    private HelpCorpusRetriever retriever() {
        return new HelpCorpusRetriever(vectorStore, HELP_CONFIG_ID, 6, 0.4);
    }

    @Test
    void returnsCitableChunksWithTheirMetadata() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("Break-glass bypasses review").score(0.87).metadata(Map.of(
                        "chunk_id", "950f5f7377e3d965",
                        "title", "Break-glass access",
                        "section", "Guides",
                        "anchor", "break-glass",
                        "url", "https://accessflow.io/docs/#break-glass")).build()));

        var chunks = retriever().retrieve("what is break-glass");

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.chunkId()).isEqualTo("950f5f7377e3d965");
            assertThat(chunk.title()).isEqualTo("Break-glass access");
            assertThat(chunk.section()).isEqualTo("Guides");
            assertThat(chunk.anchor()).isEqualTo("break-glass");
            assertThat(chunk.url()).isEqualTo("https://accessflow.io/docs/#break-glass");
            assertThat(chunk.text()).isEqualTo("Break-glass bypasses review");
            assertThat(chunk.score()).isEqualTo(0.87);
        });
    }

    @Test
    void searchesWithinTheHelpScopeOnly() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retriever().retrieve("how do I submit a query");

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        var request = captor.getValue();
        assertThat(request.getTopK()).isEqualTo(6);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.4);
        // The AF-336 knowledge base is excluded by the corpus key, other tenants by the config id.
        assertThat(request.getFilterExpression().toString())
                .contains("corpus", "help", "help_config_id", HELP_CONFIG_ID.toString());
    }

    @Test
    void toleratesAChunkWithNoMetadataAtAll() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(Document.builder().text("orphan chunk").build()));

        var chunk = retriever().retrieve("anything").getFirst();

        assertThat(chunk.chunkId()).isNull();
        assertThat(chunk.title()).isNull();
        assertThat(chunk.text()).isEqualTo("orphan chunk");
    }

    @Test
    void returnsEmptyForABlankQuestionWithoutTouchingTheStore() {
        assertThat(retriever().retrieve("   ")).isEmpty();
        assertThat(retriever().retrieve(null)).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertThat(retriever().retrieve("obscure question")).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheStoreReturnsNull() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

        assertThat(retriever().retrieve("obscure question")).isEmpty();
    }

    @Test
    void swallowsAStoreFailureRatherThanFailingTheQuestion() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("store down"));

        // An answer without citations still helps; an exception here would be a 500 on a help chat.
        assertThat(retriever().retrieve("what is a review plan")).isEmpty();
    }
}
