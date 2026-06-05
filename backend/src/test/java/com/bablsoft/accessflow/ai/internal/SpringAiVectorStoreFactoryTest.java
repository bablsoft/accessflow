package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SpringAiVectorStoreFactoryTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock EmbeddingModel embeddingModel;

    private SpringAiVectorStoreFactory factory() {
        return new SpringAiVectorStoreFactory(jdbcTemplate);
    }

    @Test
    void buildsPgVectorStore() {
        var store = factory().create(RagStoreType.PGVECTOR, embeddingModel, 1536, null, null, null);

        assertThat(store).isInstanceOf(PgVectorStore.class);
    }

    @Test
    void buildsQdrantStore() {
        var store = factory().create(RagStoreType.QDRANT, embeddingModel, 1536,
                "http://localhost:6334", "accessflow", "qdrant-key");

        assertThat(store).isInstanceOf(QdrantVectorStore.class);
    }

    @Test
    void buildsQdrantStoreWithoutApiKey() {
        var store = factory().create(RagStoreType.QDRANT, embeddingModel, 1536,
                "localhost:6334", "accessflow", null);

        assertThat(store).isInstanceOf(QdrantVectorStore.class);
    }

    @Test
    void parsesFullUrlEndpoint() {
        var target = SpringAiVectorStoreFactory.QdrantTarget.parse("https://qdrant.example.com:6334");

        assertThat(target.host()).isEqualTo("qdrant.example.com");
        assertThat(target.port()).isEqualTo(6334);
        assertThat(target.useTls()).isTrue();
    }

    @Test
    void parsesHostPortEndpoint() {
        var target = SpringAiVectorStoreFactory.QdrantTarget.parse("localhost:6334");

        assertThat(target.host()).isEqualTo("localhost");
        assertThat(target.port()).isEqualTo(6334);
        assertThat(target.useTls()).isFalse();
    }

    @Test
    void parsesBareHostUsesDefaultPort() {
        var target = SpringAiVectorStoreFactory.QdrantTarget.parse("qdrant");

        assertThat(target.host()).isEqualTo("qdrant");
        assertThat(target.port()).isEqualTo(6334);
        assertThat(target.useTls()).isFalse();
    }

    @Test
    void parsesNullEndpointToLocalhostDefault() {
        var target = SpringAiVectorStoreFactory.QdrantTarget.parse(null);

        assertThat(target.host()).isEqualTo("localhost");
        assertThat(target.port()).isEqualTo(6334);
    }
}
