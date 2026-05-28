package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.SamlSpec;
import com.bablsoft.accessflow.security.api.SamlConfigService;
import com.bablsoft.accessflow.security.api.SamlConfigView;
import com.bablsoft.accessflow.security.api.UpdateSamlConfigCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SamlReconciler {

    private final SamlConfigService samlConfigService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

    public void reconcile(UUID organizationId, SamlSpec spec) {
        if (spec == null || !spec.enabled()) {
            log.debug("Bootstrap: SAML disabled, skipping");
            return;
        }

        var specMap = specFields(spec);
        var specFingerprint = fingerprinter.fingerprint(specMap);
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.SAML_CONFIG, organizationId)
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: SAML configuration unchanged for organization {}, skipping update", organizationId);
            return;
        }

        var previous = samlConfigService.getOrDefault(organizationId);
        var previousMap = viewFields(previous);

        samlConfigService.update(organizationId, new UpdateSamlConfigCommand(
                spec.idpMetadataUrl(),
                spec.idpEntityId(),
                spec.spEntityId(),
                spec.acsUrl(),
                spec.sloUrl(),
                spec.signingCertPem(),
                spec.attrEmail(),
                spec.attrDisplayName(),
                spec.attrRole(),
                spec.attrGroups(),
                spec.groupMappings(),
                spec.defaultRole(),
                spec.active() == null ? Boolean.TRUE : spec.active()));
        log.info("Bootstrap: applied SAML configuration for organization {}", organizationId);
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.SAML_CONFIG,
                organizationId, specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.SAML_CONFIG,
                        organizationId,
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(previousMap, specMap),
                        Map.of("config_type", "saml")));
    }

    private static Map<String, Object> specFields(SamlSpec spec) {
        var map = new LinkedHashMap<String, Object>();
        map.put("enabled", spec.enabled());
        map.put("idp_metadata_url", spec.idpMetadataUrl());
        map.put("idp_entity_id", spec.idpEntityId());
        map.put("sp_entity_id", spec.spEntityId());
        map.put("acs_url", spec.acsUrl());
        map.put("slo_url", spec.sloUrl());
        map.put("signing_cert_pem", spec.signingCertPem());
        map.put("attr_email", spec.attrEmail());
        map.put("attr_display_name", spec.attrDisplayName());
        map.put("attr_role", spec.attrRole());
        map.put("attr_groups", spec.attrGroups());
        map.put("group_mappings", spec.groupMappings());
        map.put("default_role", spec.defaultRole() == null ? null : spec.defaultRole().name());
        map.put("active", spec.active() == null ? Boolean.TRUE : spec.active());
        return map;
    }

    private static Map<String, Object> viewFields(SamlConfigView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("idp_metadata_url", view.idpMetadataUrl());
        map.put("idp_entity_id", view.idpEntityId());
        map.put("sp_entity_id", view.spEntityId());
        map.put("acs_url", view.acsUrl());
        map.put("slo_url", view.sloUrl());
        map.put("attr_email", view.attrEmail());
        map.put("attr_display_name", view.attrDisplayName());
        map.put("attr_role", view.attrRole());
        map.put("attr_groups", view.attrGroups());
        map.put("group_mappings", view.groupMappings());
        map.put("default_role", view.defaultRole() == null ? null : view.defaultRole().name());
        map.put("active", view.active());
        return map;
    }
}
