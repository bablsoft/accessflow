package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.bootstrap.internal.spec.AiConfigSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiConfigReconciler {

    private final AiConfigService aiConfigService;

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

        var existing = findByName(organizationId, spec.name());
        if (existing.isPresent()) {
            var view = existing.get();
            var updated = aiConfigService.update(view.id(), organizationId, new UpdateAiConfigCommand(
                    spec.name(),
                    spec.provider(),
                    spec.model(),
                    spec.endpoint(),
                    spec.apiKey(),
                    spec.timeoutMs(),
                    spec.maxPromptTokens(),
                    spec.maxCompletionTokens()));
            log.info("Bootstrap: updated AI config '{}' (id={})", spec.name(), updated.id());
            return updated.id();
        }

        var created = aiConfigService.create(organizationId, new CreateAiConfigCommand(
                spec.name(),
                spec.provider(),
                spec.model(),
                spec.endpoint(),
                spec.apiKey(),
                spec.timeoutMs(),
                spec.maxPromptTokens(),
                spec.maxCompletionTokens()));
        log.info("Bootstrap: created AI config '{}' (id={})", spec.name(), created.id());
        return created.id();
    }

    private Optional<AiConfigView> findByName(UUID organizationId, String name) {
        return aiConfigService.list(organizationId).stream()
                .filter(view -> view.name().equalsIgnoreCase(name))
                .findFirst();
    }
}
