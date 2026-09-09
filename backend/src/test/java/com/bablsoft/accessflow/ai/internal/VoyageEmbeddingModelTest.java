package com.bablsoft.accessflow.ai.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VoyageEmbeddingModelTest {

    private static final String ENDPOINT = VoyageEmbeddingModel.DEFAULT_BASE_URL + "/embeddings";
    private static final String ONE_VECTOR = """
            {"object":"list",
             "data":[{"object":"embedding","embedding":[0.1,0.2],"index":0}],
             "model":"voyage-4","usage":{"total_tokens":4}}
            """;
    private static final String TWO_VECTORS = """
            {"object":"list",
             "data":[{"object":"embedding","embedding":[0.1,0.2],"index":0},
                     {"object":"embedding","embedding":[0.3,0.4],"index":1}],
             "model":"voyage-4","usage":{"total_tokens":9}}
            """;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private VoyageEmbeddingModel model(Integer dimensions) {
        return new VoyageEmbeddingModel(builder.build(), objectMapper, "pa-key", "voyage-4", null,
                dimensions);
    }

    @Test
    void sendsDocumentInputTypeDespiteTheStorePassingEmptyOptions() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(header("Authorization", "Bearer pa-key"))
                .andExpect(jsonPath("$.input_type").value("document"))
                .andExpect(jsonPath("$.model").value("voyage-4"))
                .andExpect(jsonPath("$.input[0]").value("alpha"))
                .andRespond(withSuccess(TWO_VECTORS, MediaType.APPLICATION_JSON));

        var vectors = model(null).embed(
                List.of(Document.builder().text("alpha").build(),
                        Document.builder().text("beta").build()),
                EmbeddingOptions.builder().build(),
                new TokenCountBatchingStrategy());

        assertThat(vectors).hasSize(2);
        assertThat(vectors.getFirst()).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void sendsQueryInputTypeForASearchString() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andExpect(jsonPath("$.input[0]").value("who touched the table"))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        assertThat(model(null).embed("who touched the table")).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void sendsDocumentInputTypeForASingleDocument() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.input_type").value("document"))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        assertThat(model(null).embed(Document.builder().text("alpha").build()))
                .containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void omitsOutputDimensionWhenNotConfigured() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("output_dimension"))))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        model(null).embed(List.of("q"));
        server.verify();
    }

    @Test
    void sendsOutputDimensionWhenConfigured() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.output_dimension").value(512))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        model(512).embed(List.of("q"));
        server.verify();
    }

    @Test
    void reSortsResultsByIndexRatherThanTrustingArrayOrder() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"data":[{"embedding":[9.0],"index":1},{"embedding":[8.0],"index":0}],
                 "model":"voyage-4","usage":{"total_tokens":2}}
                """, MediaType.APPLICATION_JSON));

        var vectors = model(null).embed(List.of("first", "second"));

        assertThat(vectors.get(0)).containsExactly(8.0f);
        assertThat(vectors.get(1)).containsExactly(9.0f);
    }

    @Test
    void surfacesANonSuccessResponseAsAnException() {
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        assertThatThrownBy(() -> model(null).embed("q"))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void dimensionsReturnsTheConfiguredValueWithoutACall() {
        assertThat(model(1024).dimensions()).isEqualTo(1024);
        server.verify(); // no request was made
    }

    @Test
    void dimensionsProbesOnlyWhenNoneIsConfigured() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        assertThat(model(null).dimensions()).isEqualTo(2);
        server.verify();
    }

    @Test
    void anUnqualifiedCallFallsBackToTheQueryInputType() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        var response = model(null).call(
                new EmbeddingRequest(List.of("x"), EmbeddingOptions.builder().build()));

        assertThat(response.getMetadata().getModel()).isEqualTo("voyage-4");
        assertThat(response.getResults()).hasSize(1);
        server.verify();
    }

    @Test
    void usesACustomBaseUrlWhenOneIsConfigured() {
        server.expect(requestTo("https://voyage.internal/v1/embeddings"))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        new VoyageEmbeddingModel(builder.build(), objectMapper, "k", "voyage-4",
                "https://voyage.internal/v1/", null).embed("q");
        server.verify();
    }

    @Test
    void refusesToEmbedADocumentWithNoText() {
        var imageOnly = Document.builder()
                .media(new Media(MediaType.IMAGE_PNG, URI.create("https://example.test/a.png")))
                .build();

        assertThatThrownBy(() -> model(null).embed(imageOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no text content");
        server.verify(); // no request was made
    }

    @Test
    void rejectsAResponseWithNoDataArray() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"detail\":\"quota exceeded\"}", MediaType.APPLICATION_JSON));

        // A 200 with an unexpected envelope must name Voyage here, not surface as an
        // IndexOutOfBoundsException thrown from inside Spring AI's batching loop.
        assertThatThrownBy(() -> model(null).embed("q"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no 'data' array")
                .hasMessageContaining("quota exceeded");
    }

    @Test
    void rejectsAShortResponseRatherThanMisalignedVectors() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model(null).embed(List.of("first", "second")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 embedding(s) for 2 input(s)");
    }

    @Test
    void optionsCanOverrideTheConfiguredModelAndDimension() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.model").value("voyage-3-large"))
                .andExpect(jsonPath("$.output_dimension").value(256))
                // No inputType on these options, so the fallback applies.
                .andExpect(jsonPath("$.input_type").value("query"))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        model(1024).call(new EmbeddingRequest(List.of("x"),
                new VoyageEmbeddingOptions("voyage-3-large", 256, null)));

        server.verify();
    }

    @Test
    void toleratesAResponseWithoutIndexOrModel() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"data":[{"embedding":[7.0]}],"usage":{}}
                """, MediaType.APPLICATION_JSON));

        var response = model(null).call(
                new EmbeddingRequest(List.of("x"), VoyageEmbeddingOptions.of(VoyageInputType.QUERY)));

        // Positional order is the fallback when index is absent, and the configured model name
        // stands in when the response omits its own.
        assertThat(response.getResults().getFirst().getOutput()).containsExactly(7.0f);
        assertThat(response.getMetadata().getModel()).isEqualTo("voyage-4");
    }

    @Test
    void abbreviatesALongUnexpectedBodyInTheErrorMessage() {
        var body = "{\"message\":\"" + "x".repeat(500) + "\"}";
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model(null).embed("q"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(300).endsWith("\u2026"));
    }

    @Test
    void treatsABlankBaseUrlAsTheVoyageDefault() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(ONE_VECTOR, MediaType.APPLICATION_JSON));

        new VoyageEmbeddingModel(builder.build(), objectMapper, "k", "voyage-4", "   ", null).embed("q");
        server.verify();
    }

    @Test
    void rejectsAnEmptyResponseBody() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model(null).embed("q"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }
}
