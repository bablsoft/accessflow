package com.bablsoft.accessflow.ai.internal;

/**
 * Resolves the effective analyzer system-prompt template at call time. The holder builds a constant
 * source (the per-config {@code system_prompt_template}) or a Langfuse-backed source that fetches
 * the managed prompt per call and falls back to the local template. Returning {@code null}/blank
 * lets {@link SystemPromptRenderer} apply the built-in default.
 */
@FunctionalInterface
interface SystemPromptSource {

    String template();
}
