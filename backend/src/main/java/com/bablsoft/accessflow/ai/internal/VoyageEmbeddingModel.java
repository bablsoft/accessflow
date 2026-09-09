package com.bablsoft.accessflow.ai.internal;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Voyage AI embeddings over its native HTTP API (AF-918). Hand-rolled over {@link RestClient}
 * following the {@link LangfuseClient} convention rather than reusing Spring AI's OpenAI client,
 * because the one parameter that matters here — {@code input_type} — is not expressible in the
 * OpenAI wire format.
 *
 * <p><strong>How the input type is decided.</strong> {@code RagComponentsFactory} builds a single
 * {@link EmbeddingModel} that serves both ingestion and search, and both vector stores call it with
 * {@code EmbeddingOptions.builder().build()} — empty options that carry nothing. What the stores do
 * differ on is <em>which overload</em> they enter through: {@code add()} goes to
 * {@link #embed(List, EmbeddingOptions, BatchingStrategy)} with documents, {@code similaritySearch()}
 * to {@link #embed(String)}. So the overload is the signal. Each entry point discards whatever
 * options it was handed and substitutes its own {@link VoyageEmbeddingOptions}, then delegates to the
 * interface default so the batching loop is reused verbatim.
 */
class VoyageEmbeddingModel implements EmbeddingModel {

    static final String DEFAULT_BASE_URL = "https://api.voyageai.com/v1";

    /**
     * What an unqualified {@link #call(EmbeddingRequest)} embeds as. Every path this class controls
     * sets the type explicitly; this covers a caller reaching {@code call()} directly — a probe or a
     * connectivity test, which are search-shaped.
     */
    private static final VoyageInputType FALLBACK_INPUT_TYPE = VoyageInputType.QUERY;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Integer dimensions;

    VoyageEmbeddingModel(RestClient restClient, ObjectMapper objectMapper, String apiKey,
            String model, String baseUrl, Integer dimensions) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var inputType = FALLBACK_INPUT_TYPE;
        String requestedModel = null;
        Integer requestedDimensions = null;
        if (request.getOptions() instanceof VoyageEmbeddingOptions voyage) {
            if (voyage.inputType() != null) {
                inputType = voyage.inputType();
            }
            requestedModel = voyage.model();
            requestedDimensions = voyage.dimensions();
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("input", request.getInstructions());
        body.put("model", requestedModel != null ? requestedModel : model);
        body.put("input_type", inputType.wireValue());
        var outputDimension = requestedDimensions != null ? requestedDimensions : dimensions;
        if (outputDimension != null) {
            body.put("output_dimension", outputDimension);
        }
        var raw = restClient.post()
                .uri(URI.create(baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        return parse(raw, request.getInstructions().size());
    }

    /** Ingestion of one document. */
    @Override
    public float[] embed(Document document) {
        var text = getEmbeddingContent(document);
        if (text == null) {
            // A media-only Document has no text to embed; sending "null" would store a vector for
            // the literal string, which nothing downstream could tell from a real one.
            throw new IllegalArgumentException("Document has no text content to embed");
        }
        var response = call(new EmbeddingRequest(List.of(text),
                VoyageEmbeddingOptions.of(VoyageInputType.DOCUMENT)));
        return response.getResults().getFirst().getOutput();
    }

    /**
     * Search. {@code embed(String)} delegates here through the interface default, so both the
     * single-text and multi-text search paths are covered by this one override.
     */
    @Override
    public List<float[]> embed(List<String> texts) {
        return call(new EmbeddingRequest(texts, VoyageEmbeddingOptions.of(VoyageInputType.QUERY)))
                .getResults().stream()
                .map(Embedding::getOutput)
                .toList();
    }

    /** Ingestion. The incoming (empty) options are discarded; the batching strategy is not. */
    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options,
            BatchingStrategy batchingStrategy) {
        return EmbeddingModel.super.embed(documents,
                VoyageEmbeddingOptions.of(VoyageInputType.DOCUMENT), batchingStrategy);
    }

    /**
     * The configured vector length, with no network call — {@code QdrantVectorStore} sizes a new
     * collection from it. Falls back to the interface default (one probe call) only when no
     * dimension is configured, in which case Voyage's per-model default is the answer and only it
     * knows what that is.
     */
    @Override
    public int dimensions() {
        return dimensions != null ? dimensions : EmbeddingModel.super.dimensions();
    }

    /**
     * Voyage documents {@code data[].index} but does not promise array order, so the results are
     * sorted by it rather than trusted positionally — the batching loop above pairs the i-th result
     * with the i-th document, and a silent transposition there would be undetectable downstream.
     */
    private EmbeddingResponse parse(String raw, int expected) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Voyage embeddings response was empty");
        }
        JsonNode root = objectMapper.readTree(raw);
        var data = root.path("data");
        // Checked here rather than left to Spring AI: the batching loop in the interface default pairs
        // the i-th result with the i-th document, so a short or shapeless response surfaces as an
        // IndexOutOfBoundsException thrown from inside Spring AI, naming neither Voyage nor the cause.
        if (!data.isArray()) {
            throw new IllegalStateException(
                    "Voyage embeddings response has no 'data' array: " + abbreviate(raw));
        }
        if (data.size() != expected) {
            throw new IllegalStateException("Voyage returned %d embedding(s) for %d input(s)"
                    .formatted(data.size(), expected));
        }
        var embeddings = new ArrayList<Embedding>(data.size());
        for (var i = 0; i < data.size(); i++) {
            var node = data.get(i);
            var vectorNode = node.path("embedding");
            var vector = new float[vectorNode.size()];
            for (var j = 0; j < vectorNode.size(); j++) {
                vector[j] = (float) vectorNode.get(j).asDouble();
            }
            var index = node.path("index").isNumber() ? node.path("index").asInt() : i;
            embeddings.add(new Embedding(vector, index));
        }
        embeddings.sort(Comparator.comparingInt(Embedding::getIndex));
        var responseModel = root.path("model").isString() ? root.path("model").stringValue() : model;
        var totalTokens = root.path("usage").path("total_tokens").asInt(0);
        return new EmbeddingResponse(List.copyOf(embeddings),
                new EmbeddingResponseMetadata(responseModel, new DefaultUsage(totalTokens, 0, totalTokens)));
    }

    /** Enough of an unexpected body to diagnose it, without logging a whole batch of vectors. */
    private static String abbreviate(String raw) {
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "…";
    }
}
