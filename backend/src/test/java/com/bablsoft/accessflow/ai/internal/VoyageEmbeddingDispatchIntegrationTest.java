package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.VectorStoreTestTable;
import com.bablsoft.accessflow.core.api.RagStoreType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression test for the whole design of {@link VoyageEmbeddingModel}: a real
 * {@code PgVectorStore} is the thing that decides which overload gets called, and the model only
 * knows whether it is indexing or searching because those overloads differ. If a future Spring AI
 * upgrade collapses them — routing both through {@code embed(List&lt;String&gt;)}, say — every
 * Voyage document would silently be embedded with the query instruction prompt and retrieval quality
 * would drop with nothing failing. This fires instead.
 *
 * <p>A {@code SimpleVectorStore} substitute would not do: its call pattern is its own, and agreeing
 * with it proves nothing about the store AccessFlow actually ships.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class VoyageEmbeddingDispatchIntegrationTest {

    private static final int DIMENSIONS = 1536;

    @Autowired JdbcTemplate jdbcTemplate;

    private final List<String> inputTypes = new ArrayList<>();

    @BeforeEach
    void startFromAnEmptyTable() {
        VectorStoreTestTable.clear(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        VectorStoreTestTable.clear(jdbcTemplate);
    }

    @Test
    void addSendsDocumentAndSimilaritySearchSendsQuery() {
        var model = new VoyageEmbeddingModel(recordingRestClient(), JsonMapper.builder().build(),
                "pa-key", "voyage-4", null, null);
        var store = new SpringAiVectorStoreFactory(jdbcTemplate)
                .create(RagStoreType.PGVECTOR, model, DIMENSIONS, null, null, null);
        var configId = UUID.randomUUID();

        store.add(List.of(Document.builder().text("alpha knowledge")
                .metadata(Map.of("ai_config_id", configId.toString(),
                        "document_id", UUID.randomUUID().toString())).build()));

        assertThat(inputTypes).containsExactly("document");

        var hits = store.similaritySearch(SearchRequest.builder()
                .query("alpha knowledge")
                .topK(5)
                .similarityThreshold(0.0)
                .filterExpression("ai_config_id == '" + configId + "'")
                .build());

        assertThat(inputTypes).containsExactly("document", "query");
        assertThat(hits).isNotEmpty();
    }

    /**
     * A {@link RestClient} that answers every Voyage call itself — recording the {@code input_type}
     * it saw and returning a deterministic one-hot vector per input text, so identical text scores a
     * cosine similarity of 1 and the search above actually returns the row it stored.
     */
    private RestClient recordingRestClient() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    var json = JsonMapper.builder().build()
                            .readTree(new String(body, StandardCharsets.UTF_8));
                    inputTypes.add(json.path("input_type").stringValue());
                    var texts = json.path("input");
                    var data = new StringBuilder("{\"data\":[");
                    for (var i = 0; i < texts.size(); i++) {
                        if (i > 0) {
                            data.append(',');
                        }
                        data.append("{\"index\":").append(i).append(",\"embedding\":")
                                .append(oneHot(texts.get(i).stringValue())).append('}');
                    }
                    data.append("],\"model\":\"voyage-4\",\"usage\":{\"total_tokens\":1}}");
                    return new CannedResponse(data.toString());
                })
                .build();
    }

    private static String oneHot(String text) {
        var slot = Math.floorMod(text.hashCode(), DIMENSIONS);
        var sb = new StringBuilder("[");
        for (var i = 0; i < DIMENSIONS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == slot ? "1.0" : "0.0");
        }
        return sb.append(']').toString();
    }

    private record CannedResponse(String body) implements ClientHttpResponse {

        @Override
        public HttpStatus getStatusCode() {
            return HttpStatus.OK;
        }

        @Override
        public String getStatusText() {
            return "OK";
        }

        @Override
        public void close() {
            // nothing to release
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public HttpHeaders getHeaders() {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }
    }
}
