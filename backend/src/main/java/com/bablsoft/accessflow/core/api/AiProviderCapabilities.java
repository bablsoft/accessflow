package com.bablsoft.accessflow.core.api;

import java.util.Set;

/**
 * The capability table for {@link AiProviderType}. {@code AiProviderType} conflates two independent
 * axes — a provider may back chat completions, embeddings, both, or (in principle) neither — and
 * before AF-918 each axis was re-derived at nine call sites as a hand-written {@code ==} / {@code !=}
 * chain that the compiler could not check. {@code ANTHROPIC} is chat-only (it has no embeddings API)
 * and {@code VOYAGE} is its mirror: embedding-only.
 *
 * <p>Every method here is an <strong>exhaustive</strong> {@code switch} with no {@code default}, so
 * adding a member to {@link AiProviderType} breaks the build on all four axes rather than silently
 * inheriting whichever answer a fallback happened to give.
 */
public final class AiProviderCapabilities {

    /** The vector lengths Voyage's models can emit. */
    private static final Set<Integer> VOYAGE_DIMENSIONS = Set.of(256, 512, 1024, 2048);

    /** What Voyage returns when no {@code output_dimension} is requested. */
    private static final Integer VOYAGE_DEFAULT_DIMENSIONS = 1024;

    private AiProviderCapabilities() {
    }

    /** Whether the provider can back a chat model — query analysis, text-to-SQL, the help agent. */
    public static boolean supportsChat(AiProviderType provider) {
        return switch (provider) {
            case OPENAI, ANTHROPIC, OLLAMA, OPENAI_COMPATIBLE, HUGGING_FACE -> true;
            case VOYAGE -> false;
        };
    }

    /** Whether the provider can back an embedding model — the RAG knowledge base and help corpus. */
    public static boolean supportsEmbedding(AiProviderType provider) {
        return switch (provider) {
            case OPENAI, OLLAMA, OPENAI_COMPATIBLE, HUGGING_FACE, VOYAGE -> true;
            case ANTHROPIC -> false;
        };
    }

    /**
     * Whether the provider can be reached without an API key. True for the self-hostable backends
     * (Ollama, any OpenAI-compatible server, a local Hugging Face TGI); the hosted vendors always
     * need a key of their own — a Voyage key is a Voyage key, not an Anthropic one.
     */
    public static boolean keylessCapable(AiProviderType provider) {
        return switch (provider) {
            case OLLAMA, OPENAI_COMPATIBLE, HUGGING_FACE -> true;
            case OPENAI, ANTHROPIC, VOYAGE -> false;
        };
    }

    /** Whether an admin-supplied base URL is mandatory — true only where there is no default host. */
    public static boolean requiresEndpoint(AiProviderType provider) {
        return switch (provider) {
            case OPENAI_COMPATIBLE -> true;
            case OPENAI, ANTHROPIC, OLLAMA, HUGGING_FACE, VOYAGE -> false;
        };
    }

    /**
     * Whether a requested embedding vector length is actually honoured when the model is built.
     * OpenAI and its wire-compatible kin take a {@code dimensions} request parameter and Voyage takes
     * {@code output_dimension}; Ollama serves whatever its model natively emits and has no such knob,
     * so accepting a length for it would let an admin satisfy the pgvector width check on paper and
     * still fail at ingest.
     */
    public static boolean supportsConfigurableDimensions(AiProviderType provider) {
        return switch (provider) {
            case OPENAI, OPENAI_COMPATIBLE, HUGGING_FACE, VOYAGE -> true;
            case OLLAMA, ANTHROPIC -> false;
        };
    }

    /**
     * The only vector lengths the provider can emit, or an empty set when any positive length is
     * allowed (the usual case — OpenAI's {@code text-embedding-3-*} shrink to arbitrary widths).
     */
    public static Set<Integer> supportedDimensions(AiProviderType provider) {
        return switch (provider) {
            case VOYAGE -> VOYAGE_DIMENSIONS;
            case OPENAI, OPENAI_COMPATIBLE, HUGGING_FACE, OLLAMA, ANTHROPIC -> Set.of();
        };
    }

    /**
     * The vector length the provider emits when none is requested, or {@code null} when only the
     * provider knows (it varies by model). A pinned value is what lets the pgvector width check fire
     * on a configuration that left the field blank.
     */
    public static Integer defaultDimensions(AiProviderType provider) {
        return switch (provider) {
            case VOYAGE -> VOYAGE_DEFAULT_DIMENSIONS;
            case OPENAI, OPENAI_COMPATIBLE, HUGGING_FACE, OLLAMA, ANTHROPIC -> null;
        };
    }
}
