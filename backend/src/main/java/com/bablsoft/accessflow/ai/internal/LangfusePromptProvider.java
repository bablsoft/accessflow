package com.bablsoft.accessflow.ai.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the analyzer system prompt from Langfuse for a given prompt name + label. Returns empty
 * when Langfuse / prompt-management is disabled for the org or the fetch fails, so the caller falls
 * back to the locally stored template.
 */
interface LangfusePromptProvider {

    Optional<String> resolve(UUID organizationId, String promptName, String promptLabel);
}
