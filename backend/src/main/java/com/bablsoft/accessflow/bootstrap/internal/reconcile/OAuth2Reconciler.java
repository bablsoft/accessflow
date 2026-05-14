package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.OAuth2Spec;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2Reconciler {

    private final OAuth2ConfigService oauth2ConfigService;

    public void reconcile(UUID organizationId, List<OAuth2Spec> specs) {
        for (var spec : specs) {
            applyOne(organizationId, spec);
        }
    }

    private void applyOne(UUID organizationId, OAuth2Spec spec) {
        if (spec.provider() == null) {
            throw new IllegalStateException("OAuth2 spec is missing 'provider'");
        }
        if (spec.clientId() == null || spec.clientId().isBlank()) {
            throw new IllegalStateException(
                    "OAuth2 provider %s is missing 'clientId'".formatted(spec.provider()));
        }
        oauth2ConfigService.update(organizationId, spec.provider(), new UpdateOAuth2ConfigCommand(
                spec.clientId(),
                spec.clientSecret(),
                spec.scopesOverride(),
                spec.tenantId(),
                spec.defaultRole(),
                spec.active() == null ? Boolean.TRUE : spec.active()));
        log.info("Bootstrap: applied OAuth2 provider {} for organization {}", spec.provider(), organizationId);
    }
}
