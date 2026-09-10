package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.VectorStoreTestTable;
import com.bablsoft.accessflow.ai.internal.help.HelpCorpusRetriever;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression test the whole help-corpus design rests on (AF-902, epic AF-899).
 *
 * <p>Help chunks and AF-336 knowledge-base chunks share one {@code vector_store} table. They are kept
 * apart by metadata alone: knowledge chunks carry {@code ai_config_id} and no {@code corpus} key, help
 * chunks carry {@code corpus} / {@code help_config_id} and <strong>no</strong> {@code ai_config_id}. A
 * missing key is SQL {@code NULL} and never equals anything, so each retriever's filter excludes the
 * other's rows with no change to {@code DefaultRagRetriever} at all.
 *
 * <p>That property is invisible in normal operation and silent when broken — {@code retrieve()}
 * returns {@code null} on no results rather than erroring, so a leak would look like a slightly odd
 * answer, and a regression that hid every customer's knowledge base would look like an empty prompt.
 * Hence a test that seeds one of each and asserts each retriever sees exactly its own.
 *
 * <p>It lives in {@code ai.internal} rather than {@code ai.internal.help} because
 * {@code DefaultRagRetriever} is package-private here, which is itself part of the point: the class
 * this protects is not meant to be reachable, let alone edited, from the help package.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpCorpusIsolationIntegrationTest {

    private static final int DIMENSIONS = 1536;
    private static final String SHARED_TEXT = "Break-glass access bypasses review and is fully audited";

    @Autowired JdbcTemplate jdbcTemplate;

    private final UUID aiConfigId = UUID.randomUUID();
    private final UUID helpConfigId = UUID.randomUUID();

    private VectorStore store;

    @BeforeEach
    void seedBothCorpora() {
        store = new SpringAiVectorStoreFactory(jdbcTemplate)
                .create(RagStoreType.PGVECTOR, new StubEmbeddingModel(), DIMENSIONS, null, null, null);
        VectorStoreTestTable.clear(jdbcTemplate);
        // Identical text in both, so nothing but the metadata filter can separate them.
        store.add(List.of(
                Document.builder().text(SHARED_TEXT).metadata(Map.of(
                        "ai_config_id", aiConfigId.toString(),
                        "document_id", UUID.randomUUID().toString(),
                        "organization_id", UUID.randomUUID().toString(),
                        "title", "Knowledge base document")).build(),
                Document.builder().text(SHARED_TEXT).metadata(Map.of(
                        "corpus", "help",
                        "help_config_id", helpConfigId.toString(),
                        "organization_id", UUID.randomUUID().toString(),
                        "corpus_version", "b3afb123fe5b",
                        "chunk_id", "950f5f7377e3d965",
                        "title", "Break-glass access",
                        "section", "Guides",
                        "anchor", "break-glass",
                        "url", "https://accessflow.io/docs/#break-glass")).build()));
    }

    @AfterEach
    void cleanUp() {
        VectorStoreTestTable.clear(jdbcTemplate);
    }

    @Test
    void theKnowledgeBaseRetrieverNeverSeesHelpChunks() {
        var retriever = new DefaultRagRetriever(store, aiConfigId, 10, 0.0);

        var context = retriever.retrieve(SHARED_TEXT);

        assertThat(context).isNotNull();
        // One occurrence, not two: the help chunk holds the same text and must not have been joined in.
        assertThat(context.split(SHARED_TEXT, -1)).hasSize(2);
    }

    @Test
    void theHelpRetrieverNeverSeesKnowledgeBaseChunks() {
        var retriever = new HelpCorpusRetriever(store, helpConfigId, 10, 0.0);

        var chunks = retriever.retrieve(SHARED_TEXT);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().chunkId()).isEqualTo("950f5f7377e3d965");
        assertThat(chunks.getFirst().title()).isEqualTo("Break-glass access");
        assertThat(chunks.getFirst().url()).isEqualTo("https://accessflow.io/docs/#break-glass");
    }

    @Test
    void aHelpConfigNeverSeesAnotherHelpConfigsChunks() {
        var retriever = new HelpCorpusRetriever(store, UUID.randomUUID(), 10, 0.0);

        assertThat(retriever.retrieve(SHARED_TEXT)).isEmpty();
    }

    @Test
    void helpChunksCarryNoAiConfigIdKeyAtAll() {
        Integer withAiConfigId = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata::jsonb ? 'corpus' "
                        + "AND metadata::jsonb ? 'ai_config_id'", Integer.class);

        assertThat(withAiConfigId).isZero();
    }

    /** Deterministic one-hot embedding: identical text to identical vector (cosine similarity 1). */
    private static final class StubEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = new ArrayList<Embedding>();
            var texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(vectorFor(texts.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorFor(document.getText());
        }

        @Override
        public float[] embed(String text) {
            return vectorFor(text);
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        private static float[] vectorFor(String text) {
            var vector = new float[DIMENSIONS];
            vector[Math.floorMod(text.hashCode(), DIMENSIONS)] = 1.0f;
            return vector;
        }
    }
}
