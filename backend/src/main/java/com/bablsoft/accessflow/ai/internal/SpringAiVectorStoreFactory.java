package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.RagStoreType;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Production {@link VectorStoreFactory}. PGVECTOR reuses the shared application {@link JdbcTemplate}
 * and the Flyway-created {@code vector_store} table ({@code initializeSchema=false}); QDRANT builds a
 * gRPC client per config from the endpoint / key. Both partition rows by an {@code ai_config_id}
 * metadata / payload entry so configs and orgs coexist in one store.
 */
@Component
@RequiredArgsConstructor
class SpringAiVectorStoreFactory implements VectorStoreFactory {

    private static final String VECTOR_TABLE = "vector_store";
    private static final int DEFAULT_QDRANT_PORT = 6334;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorStore create(RagStoreType type, EmbeddingModel embeddingModel, int dimensions,
                              String endpoint, String collection, String apiKey) {
        return switch (type) {
            case PGVECTOR -> pgVector(embeddingModel, dimensions);
            case QDRANT -> qdrant(embeddingModel, endpoint, collection, apiKey);
        };
    }

    private VectorStore pgVector(EmbeddingModel embeddingModel, int dimensions) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .schemaName("public")
                .vectorTableName(VECTOR_TABLE)
                .build();
    }

    private VectorStore qdrant(EmbeddingModel embeddingModel, String endpoint, String collection,
                               String apiKey) {
        var target = QdrantTarget.parse(endpoint);
        var grpcBuilder = QdrantGrpcClient.newBuilder(target.host(), target.port(), target.useTls());
        if (apiKey != null && !apiKey.isBlank()) {
            grpcBuilder.withApiKey(apiKey);
        }
        var client = new QdrantClient(grpcBuilder.build());
        return QdrantVectorStore.builder(client, embeddingModel)
                .collectionName(collection)
                .initializeSchema(false)
                .build();
    }

    /** host / port / TLS resolved from a Qdrant gRPC endpoint (URL or {@code host:port}). */
    record QdrantTarget(String host, int port, boolean useTls) {

        static QdrantTarget parse(String endpoint) {
            var raw = endpoint == null ? "" : endpoint.trim();
            if (raw.isEmpty()) {
                return new QdrantTarget("localhost", DEFAULT_QDRANT_PORT, false);
            }
            var withScheme = raw.contains("://") ? raw : "grpc://" + raw;
            var uri = URI.create(withScheme);
            var host = uri.getHost() != null ? uri.getHost() : "localhost";
            var port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_QDRANT_PORT;
            var useTls = "https".equalsIgnoreCase(uri.getScheme());
            return new QdrantTarget(host, port, useTls);
        }
    }
}
