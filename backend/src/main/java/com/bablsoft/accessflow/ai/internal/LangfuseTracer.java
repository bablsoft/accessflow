package com.bablsoft.accessflow.ai.internal;

/**
 * Emits one Langfuse trace per analyzer LLM call. Implementations must be non-blocking and
 * best-effort: a tracing failure (or a disabled / unconfigured org) never affects the analysis.
 */
interface LangfuseTracer {

    void trace(LangfuseTraceContext context);
}
