package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;

import java.util.UUID;

/**
 * One orchestration member in a {@link CreateAiConfigCommand} / {@link UpdateAiConfigCommand}
 * (AF-450). Members run in parallel alongside the primary {@code ai_config} model and vote.
 *
 * <p>{@code id} identifies an existing member on update (so its masked key can be preserved);
 * {@code null} means "insert a new member". {@code apiKey} follows the same masking semantics as the
 * parent config: {@code null} / {@code "********"} keeps the stored key, a blank string clears it,
 * any other value is encrypted. {@code weight} defaults to {@code 1.0} and {@code enabled} to
 * {@code true} when {@code null}.
 */
public record AiConfigModelCommand(
        UUID id,
        AiProviderType provider,
        String model,
        String endpoint,
        String apiKey,
        Double weight,
        Boolean enabled) {
}
