package com.bablsoft.accessflow.core.api;

/**
 * An AI vendor / API surface an {@code ai_config} row can point at. Membership does <em>not</em>
 * imply a provider is usable everywhere: the enum spans two independent capability axes — chat
 * completions and embeddings — and no member has ever satisfied both for every value.
 * {@code ANTHROPIC} is chat-only (Anthropic publishes no embeddings API) and {@code VOYAGE} is
 * embedding-only. Ask {@link AiProviderCapabilities} rather than comparing values by hand.
 */
public enum AiProviderType {

    /** OpenAI's hosted API — chat and embeddings. */
    OPENAI,

    /** Anthropic's Claude API — chat only; it has no embeddings endpoint. */
    ANTHROPIC,

    /** A self-hosted Ollama server — chat and embeddings, keyless. */
    OLLAMA,

    /** Any OpenAI-wire-compatible server at an admin-supplied base URL — chat and embeddings. */
    OPENAI_COMPATIBLE,

    /** The Hugging Face Inference Providers router or a local TGI server — chat and embeddings. */
    HUGGING_FACE,

    /**
     * Voyage AI's embeddings API — embeddings only. A third-party vendor with its own API key,
     * recommended by Anthropic for teams running Claude for analysis, who would otherwise have no
     * embedding path at all.
     */
    VOYAGE
}
