package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.SystemSmtpSpec;
import com.bablsoft.accessflow.core.api.SaveSystemSmtpCommand;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.SystemSmtpView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SystemSmtpReconciler {

    private final SystemSmtpService systemSmtpService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public void reconcile(UUID organizationId, SystemSmtpSpec spec) {
        if (spec == null || !spec.enabled()) {
            log.debug("Bootstrap: system SMTP disabled, skipping");
            return;
        }
        if (spec.host() == null || spec.host().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.systemSmtp.host is required when enabled");
        }
        if (spec.port() == null) {
            throw new IllegalStateException("accessflow.bootstrap.systemSmtp.port is required when enabled");
        }
        if (spec.fromAddress() == null || spec.fromAddress().isBlank()) {
            throw new IllegalStateException(
                    "accessflow.bootstrap.systemSmtp.fromAddress is required when enabled");
        }

        var specMap = specFields(spec);
        var specFingerprint = fingerprinter.fingerprint(specMap);
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.SYSTEM_SMTP, organizationId)
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: system SMTP unchanged for organization {}, skipping update", organizationId);
            return;
        }

        var previousMap = systemSmtpService.findForOrganization(organizationId)
                .map(SystemSmtpReconciler::viewFields)
                .orElseGet(Map::of);

        systemSmtpService.saveOrUpdate(organizationId, new SaveSystemSmtpCommand(
                spec.host(),
                spec.port(),
                spec.username(),
                spec.password(),
                spec.tls() == null || spec.tls(),
                spec.fromAddress(),
                spec.fromName()));
        log.info("Bootstrap: applied system SMTP configuration for organization {}", organizationId);
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.SYSTEM_SMTP,
                organizationId, specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.SYSTEM_SMTP,
                        organizationId,
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(previousMap, specMap),
                        Map.of("config_type", "system_smtp")));
    }

    private static Map<String, Object> specFields(SystemSmtpSpec spec) {
        var map = new LinkedHashMap<String, Object>();
        map.put("host", spec.host());
        map.put("port", spec.port());
        map.put("username", spec.username());
        map.put("password", spec.password());
        map.put("tls", spec.tls() == null || spec.tls());
        map.put("from_address", spec.fromAddress());
        map.put("from_name", spec.fromName());
        return map;
    }

    private static Map<String, Object> viewFields(SystemSmtpView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("host", view.host());
        map.put("port", view.port());
        map.put("username", view.username());
        map.put("tls", view.tls());
        map.put("from_address", view.fromAddress());
        map.put("from_name", view.fromName());
        return map;
    }
}
