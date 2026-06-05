package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the V69 {@code vector_store} DDL + the {@code vector} extension (test init script) work
 * end-to-end with Spring AI's {@code PgVectorStore} built by {@link SpringAiVectorStoreFactory} with
 * {@code initializeSchema=false}. A deterministic stub {@link EmbeddingModel} avoids any external
 * provider call, so the test exercises storage, retrieval and {@code ai_config_id} metadata scoping.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class RagPgVectorStoreIntegrationTest {

    private static final int DIMENSIONS = 1536;

    @Autowired JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM vector_store");
    }

    @Test
    void storesAndRetrievesChunksScopedByAiConfig() {
        var store = new SpringAiVectorStoreFactory(jdbcTemplate)
                .create(RagStoreType.PGVECTOR, new StubEmbeddingModel(), DIMENSIONS, null, null, null);

        var configA = UUID.randomUUID();
        var configB = UUID.randomUUID();
        store.add(List.of(
                Document.builder().text("alpha knowledge")
                        .metadata(Map.of("ai_config_id", configA.toString(),
                                "document_id", UUID.randomUUID().toString())).build(),
                Document.builder().text("beta knowledge")
                        .metadata(Map.of("ai_config_id", configB.toString(),
                                "document_id", UUID.randomUUID().toString())).build()));

        var hits = store.similaritySearch(SearchRequest.builder()
                .query("alpha knowledge")
                .topK(5)
                .similarityThreshold(0.0)
                .filterExpression("ai_config_id == '" + configA + "'")
                .build());

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(d -> configA.toString().equals(d.getMetadata().get("ai_config_id")));
    }

    @Test
    void vectorStoreTableAndExtensionExist() {
        Integer extensions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        Integer columns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'vector_store' AND column_name = 'embedding'", Integer.class);

        assertThat(extensions).isEqualTo(1);
        assertThat(columns).isEqualTo(1);
    }

    /** Deterministic one-hot embedding: identical text → identical vector (cosine sim 1). */
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
