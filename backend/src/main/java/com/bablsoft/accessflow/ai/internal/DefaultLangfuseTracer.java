package com.bablsoft.accessflow.ai.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Posts analysis traces to the Langfuse ingestion API on a virtual-thread executor. Resolves the
 * org's credentials (cheap, cached) on the calling thread to skip disabled orgs without spawning a
 * task; everything else — JSON assembly and the HTTP call — runs off the request thread and any
 * failure is logged and swallowed.
 */
@Component
class DefaultLangfuseTracer implements LangfuseTracer {

    private static final Logger log = LoggerFactory.getLogger(DefaultLangfuseTracer.class);
    private static final String TRACE_NAME = "sql-analysis";

    private final LangfuseConfigResolver configResolver;
    private final LangfuseClient client;
    private final Executor executor;

    DefaultLangfuseTracer(LangfuseConfigResolver configResolver, LangfuseClient client,
                          @Qualifier("langfuseTracingExecutor") Executor executor) {
        this.configResolver = configResolver;
        this.client = client;
        this.executor = executor;
    }

    @Override
    public void trace(LangfuseTraceContext context) {
        var resolved = configResolver.resolve(context.organizationId()).orElse(null);
        if (resolved == null || !resolved.tracingEnabled()) {
            return;
        }
        executor.execute(() -> post(resolved, context));
    }

    private void post(ResolvedLangfuseConfig config, LangfuseTraceContext context) {
        try {
            client.ingest(config, buildBatch(context));
        } catch (RuntimeException e) {
            log.warn("Langfuse trace ingestion failed for org={} ai_config={}: {}",
                    context.organizationId(), context.aiConfigId(), e.getMessage());
        }
    }

    private static Map<String, Object> buildBatch(LangfuseTraceContext ctx) {
        var traceId = UUID.randomUUID().toString();
        var input = input(ctx);
        var output = output(ctx);
        var metadata = metadata(ctx);

        var traceBody = new LinkedHashMap<String, Object>();
        traceBody.put("id", traceId);
        traceBody.put("name", TRACE_NAME);
        traceBody.put("timestamp", ctx.startTime().toString());
        traceBody.put("input", input);
        traceBody.put("output", output);
        traceBody.put("metadata", metadata);
        traceBody.put("tags", List.of("accessflow", TRACE_NAME));

        var generationBody = new LinkedHashMap<String, Object>();
        generationBody.put("id", UUID.randomUUID().toString());
        generationBody.put("traceId", traceId);
        generationBody.put("type", "GENERATION");
        generationBody.put("name", TRACE_NAME);
        generationBody.put("startTime", ctx.startTime().toString());
        generationBody.put("endTime", ctx.endTime().toString());
        generationBody.put("model", model(ctx));
        generationBody.put("input", input);
        generationBody.put("output", output);
        generationBody.put("metadata", metadata);
        usageDetails(ctx).ifPresent(usage -> generationBody.put("usageDetails", usage));
        if (ctx.errorMessage() != null) {
            generationBody.put("level", "ERROR");
            generationBody.put("statusMessage", ctx.errorMessage());
        } else {
            generationBody.put("level", "DEFAULT");
        }

        var batch = new ArrayList<Map<String, Object>>(2);
        batch.add(event("trace-create", ctx.startTime().toString(), traceBody));
        batch.add(event("generation-create", ctx.endTime().toString(), generationBody));
        var root = new LinkedHashMap<String, Object>();
        root.put("batch", batch);
        return root;
    }

    private static Map<String, Object> event(String type, String timestamp, Map<String, Object> body) {
        var event = new LinkedHashMap<String, Object>();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("timestamp", timestamp);
        event.put("body", body);
        return event;
    }

    private static Map<String, Object> input(LangfuseTraceContext ctx) {
        var input = new LinkedHashMap<String, Object>();
        input.put("sql", ctx.sql());
        input.put("db_type", ctx.dbType() == null ? null : ctx.dbType().name());
        input.put("schema_context", ctx.schemaContext());
        input.put("language", ctx.language());
        return input;
    }

    private static Object output(LangfuseTraceContext ctx) {
        var result = ctx.result();
        if (result == null) {
            return null;
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("risk_score", result.riskScore());
        output.put("risk_level", result.riskLevel() == null ? null : result.riskLevel().name());
        output.put("summary", result.summary());
        output.put("issue_count", result.issues() == null ? 0 : result.issues().size());
        output.put("missing_indexes_detected", result.missingIndexesDetected());
        output.put("affects_row_estimate", result.affectsRowEstimate());
        return output;
    }

    private static Map<String, Object> metadata(LangfuseTraceContext ctx) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("organization_id", str(ctx.organizationId()));
        metadata.put("ai_config_id", str(ctx.aiConfigId()));
        metadata.put("provider", ctx.configuredProvider() == null ? null : ctx.configuredProvider().name());
        return metadata;
    }

    private static String model(LangfuseTraceContext ctx) {
        var result = ctx.result();
        if (result != null && result.aiModel() != null && !result.aiModel().isBlank()) {
            return result.aiModel();
        }
        return ctx.configuredModel();
    }

    private static java.util.Optional<Map<String, Object>> usageDetails(LangfuseTraceContext ctx) {
        var result = ctx.result();
        if (result == null) {
            return java.util.Optional.empty();
        }
        var usage = new LinkedHashMap<String, Object>();
        usage.put("input", result.promptTokens());
        usage.put("output", result.completionTokens());
        return java.util.Optional.of(usage);
    }

    private static String str(UUID value) {
        return value == null ? null : value.toString();
    }
}
