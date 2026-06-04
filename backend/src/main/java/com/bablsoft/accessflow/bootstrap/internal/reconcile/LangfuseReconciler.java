package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.ai.api.LangfuseConfigService;
import com.bablsoft.accessflow.ai.api.LangfuseConfigView;
import com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand;
import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.LangfuseSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class LangfuseReconciler {

    private final LangfuseConfigService langfuseConfigService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public void reconcile(UUID organizationId, LangfuseSpec spec) {
        if (spec == null || !spec.enabled()) {
            log.debug("Bootstrap: Langfuse disabled, skipping");
            return;
        }

        var specMap = specFields(spec);
        var specFingerprint = fingerprinter.fingerprint(specMap);
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.LANGFUSE_CONFIG, organizationId)
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: Langfuse configuration unchanged for organization {}, skipping update", organizationId);
            return;
        }

        var previous = langfuseConfigService.getOrDefault(organizationId);
        var previousMap = viewFields(previous);

        langfuseConfigService.update(organizationId, new UpdateLangfuseConfigCommand(
                Boolean.TRUE,
                spec.host(),
                spec.publicKey(),
                spec.secretKey(),
                spec.tracingEnabled() == null ? Boolean.TRUE : spec.tracingEnabled(),
                spec.promptManagementEnabled() == null ? Boolean.FALSE : spec.promptManagementEnabled()));
        log.info("Bootstrap: applied Langfuse configuration for organization {}", organizationId);
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.LANGFUSE_CONFIG,
                organizationId, specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.LANGFUSE_CONFIG,
                        organizationId,
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(previousMap, specMap),
                        Map.of("config_type", "langfuse")));
    }

    private static Map<String, Object> specFields(LangfuseSpec spec) {
        var map = new LinkedHashMap<String, Object>();
        map.put("enabled", spec.enabled());
        map.put("host", spec.host());
        map.put("public_key", spec.publicKey());
        map.put("secret_key", spec.secretKey());
        map.put("tracing_enabled", spec.tracingEnabled() == null ? Boolean.TRUE : spec.tracingEnabled());
        map.put("prompt_management_enabled",
                spec.promptManagementEnabled() == null ? Boolean.FALSE : spec.promptManagementEnabled());
        return map;
    }

    private static Map<String, Object> viewFields(LangfuseConfigView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("enabled", view.enabled());
        map.put("host", view.host());
        map.put("public_key", view.publicKey());
        map.put("tracing_enabled", view.tracingEnabled());
        map.put("prompt_management_enabled", view.promptManagementEnabled());
        return map;
    }
}
