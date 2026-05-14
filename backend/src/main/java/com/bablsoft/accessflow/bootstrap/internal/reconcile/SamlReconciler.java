package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.SamlSpec;
import com.bablsoft.accessflow.security.api.SamlConfigService;
import com.bablsoft.accessflow.security.api.UpdateSamlConfigCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SamlReconciler {

    private final SamlConfigService samlConfigService;

    public void reconcile(UUID organizationId, SamlSpec spec) {
        if (spec == null || !spec.enabled()) {
            log.debug("Bootstrap: SAML disabled, skipping");
            return;
        }
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
                spec.defaultRole(),
                spec.active() == null ? Boolean.TRUE : spec.active()));
        log.info("Bootstrap: applied SAML configuration for organization {}", organizationId);
    }
}
