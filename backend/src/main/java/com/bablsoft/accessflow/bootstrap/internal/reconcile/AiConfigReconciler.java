package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.AiConfigSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiConfigReconciler {

    private final AiConfigService aiConfigService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public Map<String, UUID> reconcile(UUID organizationId, List<AiConfigSpec> specs) {
        var byName = new HashMap<String, UUID>();
        for (var spec : specs) {
            var id = applyOne(organizationId, spec);
            byName.put(spec.name(), id);
        }
        return Map.copyOf(byName);
    }

    private UUID applyOne(UUID organizationId, AiConfigSpec spec) {
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalStateException("AI config spec is missing 'name'");
        }
        if (spec.provider() == null) {
            throw new IllegalStateException("AI config '%s' is missing 'provider'".formatted(spec.name()));
        }
        if (spec.model() == null || spec.model().isBlank()) {
            throw new IllegalStateException("AI config '%s' is missing 'model'".formatted(spec.name()));
        }

        var specMap = specFields(spec);
        var specFingerprint = fingerprinter.fingerprint(specMap);

        var existing = findByName(organizationId, spec.name());
        if (existing.isEmpty()) {
            var created = aiConfigService.create(organizationId, new CreateAiConfigCommand(
                    spec.name(),
                    spec.provider(),
                    spec.model(),
                    spec.endpoint(),
                    spec.apiKey(),
                    spec.timeoutMs(),
                    spec.maxPromptTokens(),
                    spec.maxCompletionTokens(),
                    // Bootstrap does not manage the analyzer prompt template — created configs use
                    // the built-in default; a template later set via the admin UI is left untouched.
                    // (RAG / embedding settings are likewise admin-managed — configs default to RAG off.)
                    null,
                    spec.langfusePromptName(),
                    spec.langfusePromptLabel(),
                    spec.fallbackPriority()));
            log.info("Bootstrap: created AI config '{}' (id={})", spec.name(), created.id());
            stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.AI_CONFIG,
                    created.id(), specFingerprint,
                    new BootstrapResourceUpsertedEvent(
                            organizationId,
                            BootstrapResourceType.AI_CONFIG,
                            created.id(),
                            BootstrapChangeKind.CREATE,
                            List.of(),
                            Map.of("name", created.name(), "provider", created.provider().name())));
            return created.id();
        }

        var view = existing.get();
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.AI_CONFIG, view.id())
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: AI config '{}' unchanged, skipping update", spec.name());
            return view.id();
        }

        var viewMap = viewFields(view);
        var updated = aiConfigService.update(view.id(), organizationId, new UpdateAiConfigCommand(
                spec.name(),
                spec.provider(),
                spec.model(),
                spec.endpoint(),
                spec.apiKey(),
                spec.timeoutMs(),
                spec.maxPromptTokens(),
                spec.maxCompletionTokens(),
                // null = leave the analyzer prompt template unchanged (bootstrap doesn't manage it).
                // (RAG / embedding settings are likewise left unchanged — admin-managed.)
                null,
                spec.langfusePromptName(),
                spec.langfusePromptLabel(),
                // Declarative: a spec without fallbackPriority means "not a fallback" (-1 clears).
                spec.fallbackPriority() == null ? -1 : spec.fallbackPriority()));
        log.info("Bootstrap: updated AI config '{}' (id={})", spec.name(), updated.id());
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.AI_CONFIG,
                updated.id(), specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.AI_CONFIG,
                        updated.id(),
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(viewMap, specMap),
                        Map.of("name", updated.name(), "provider", updated.provider().name())));
        return updated.id();
    }

    private Optional<AiConfigView> findByName(UUID organizationId, String name) {
        return aiConfigService.list(organizationId).stream()
                .filter(view -> view.name().equalsIgnoreCase(name))
                .findFirst();
    }

    private static Map<String, Object> specFields(AiConfigSpec spec) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", spec.name());
        map.put("provider", spec.provider().name());
        map.put("model", spec.model());
        map.put("endpoint", spec.endpoint());
        map.put("api_key", spec.apiKey());
        map.put("timeout_ms", spec.timeoutMs());
        map.put("max_prompt_tokens", spec.maxPromptTokens());
        map.put("max_completion_tokens", spec.maxCompletionTokens());
        map.put("langfuse_prompt_name", spec.langfusePromptName());
        map.put("langfuse_prompt_label", spec.langfusePromptLabel());
        map.put("fallback_priority", spec.fallbackPriority());
        return map;
    }

    private static Map<String, Object> viewFields(AiConfigView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", view.name());
        map.put("provider", view.provider().name());
        map.put("model", view.model());
        map.put("endpoint", view.endpoint());
        map.put("timeout_ms", view.timeoutMs());
        map.put("max_prompt_tokens", view.maxPromptTokens());
        map.put("max_completion_tokens", view.maxCompletionTokens());
        map.put("langfuse_prompt_name", view.langfusePromptName());
        map.put("langfuse_prompt_label", view.langfusePromptLabel());
        map.put("fallback_priority", view.fallbackPriority());
        return map;
    }
}
