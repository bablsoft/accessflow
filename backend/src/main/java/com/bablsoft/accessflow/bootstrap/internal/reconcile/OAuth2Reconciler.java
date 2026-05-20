package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.BootstrapStateTracker;
import com.bablsoft.accessflow.bootstrap.internal.SpecFingerprinter;
import com.bablsoft.accessflow.bootstrap.internal.spec.OAuth2Spec;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.OAuth2ConfigView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2Reconciler {

    private final OAuth2ConfigService oauth2ConfigService;
    private final BootstrapStateTracker stateTracker;
    private final SpecFingerprinter fingerprinter;

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
        if (spec.provider() == OAuth2ProviderType.OIDC) {
            requireOidcSpecField(spec.displayName(), "displayName");
            requireOidcSpecField(spec.authorizationUri(), "authorizationUri");
            requireOidcSpecField(spec.tokenUri(), "tokenUri");
            requireOidcSpecField(spec.userInfoUri(), "userInfoUri");
            requireOidcSpecField(spec.jwkSetUri(), "jwkSetUri");
            requireOidcSpecField(spec.issuerUri(), "issuerUri");
        }

        var providerResourceId = providerResourceId(spec.provider().name());
        var specMap = specFields(spec);
        var specFingerprint = fingerprinter.fingerprint(specMap);
        var storedFingerprint = stateTracker
                .findFingerprint(organizationId, BootstrapResourceType.OAUTH2_CONFIG, providerResourceId)
                .orElse(null);
        if (specFingerprint.equals(storedFingerprint)) {
            log.debug("Bootstrap: OAuth2 provider {} unchanged for organization {}, skipping update",
                    spec.provider(), organizationId);
            return;
        }

        var previous = oauth2ConfigService.getOrDefault(organizationId, spec.provider());
        var previousMap = viewFields(previous);

        oauth2ConfigService.update(organizationId, spec.provider(), new UpdateOAuth2ConfigCommand(
                spec.clientId(),
                spec.clientSecret(),
                spec.scopesOverride(),
                spec.tenantId(),
                spec.displayName(),
                spec.authorizationUri(),
                spec.tokenUri(),
                spec.userInfoUri(),
                spec.jwkSetUri(),
                spec.issuerUri(),
                spec.userNameAttribute(),
                spec.emailAttribute(),
                spec.emailVerifiedAttribute(),
                spec.displayNameAttribute(),
                spec.groupsAttribute(),
                spec.allowedOrganizations(),
                spec.allowedEmailDomains(),
                spec.defaultRole(),
                spec.active() == null ? Boolean.TRUE : spec.active()));
        log.info("Bootstrap: applied OAuth2 provider {} for organization {}", spec.provider(), organizationId);
        stateTracker.recordFingerprintAndPublish(organizationId, BootstrapResourceType.OAUTH2_CONFIG,
                providerResourceId, specFingerprint,
                new BootstrapResourceUpsertedEvent(
                        organizationId,
                        BootstrapResourceType.OAUTH2_CONFIG,
                        providerResourceId,
                        BootstrapChangeKind.UPDATE,
                        fingerprinter.diff(previousMap, specMap),
                        Map.of(
                                "config_type", "oauth2",
                                "provider", spec.provider().name())));
    }

    static UUID providerResourceId(String providerName) {
        return UUID.nameUUIDFromBytes(("OAUTH2:" + providerName).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireOidcSpecField(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "OAuth2 OIDC provider is missing '%s'".formatted(fieldName));
        }
    }

    private static Map<String, Object> specFields(OAuth2Spec spec) {
        var map = new LinkedHashMap<String, Object>();
        map.put("provider", spec.provider().name());
        map.put("client_id", spec.clientId());
        map.put("client_secret", spec.clientSecret());
        map.put("scopes_override", spec.scopesOverride());
        map.put("tenant_id", spec.tenantId());
        map.put("display_name", spec.displayName());
        map.put("authorization_uri", spec.authorizationUri());
        map.put("token_uri", spec.tokenUri());
        map.put("user_info_uri", spec.userInfoUri());
        map.put("jwk_set_uri", spec.jwkSetUri());
        map.put("issuer_uri", spec.issuerUri());
        map.put("user_name_attribute", spec.userNameAttribute());
        map.put("email_attribute", spec.emailAttribute());
        map.put("email_verified_attribute", spec.emailVerifiedAttribute());
        map.put("display_name_attribute", spec.displayNameAttribute());
        map.put("groups_attribute", spec.groupsAttribute());
        map.put("allowed_organizations", spec.allowedOrganizations());
        map.put("allowed_email_domains", spec.allowedEmailDomains());
        map.put("default_role", spec.defaultRole() == null ? null : spec.defaultRole().name());
        map.put("active", spec.active() == null ? Boolean.TRUE : spec.active());
        return map;
    }

    private static Map<String, Object> viewFields(OAuth2ConfigView view) {
        var map = new LinkedHashMap<String, Object>();
        map.put("provider", view.provider().name());
        map.put("client_id", view.clientId());
        map.put("scopes_override", view.scopesOverride());
        map.put("tenant_id", view.tenantId());
        map.put("display_name", view.displayName());
        map.put("authorization_uri", view.authorizationUri());
        map.put("token_uri", view.tokenUri());
        map.put("user_info_uri", view.userInfoUri());
        map.put("jwk_set_uri", view.jwkSetUri());
        map.put("issuer_uri", view.issuerUri());
        map.put("user_name_attribute", view.userNameAttribute());
        map.put("email_attribute", view.emailAttribute());
        map.put("email_verified_attribute", view.emailVerifiedAttribute());
        map.put("display_name_attribute", view.displayNameAttribute());
        map.put("groups_attribute", view.groupsAttribute());
        map.put("allowed_organizations", view.allowedOrganizations());
        map.put("allowed_email_domains", view.allowedEmailDomains());
        map.put("default_role", view.defaultRole() == null ? null : view.defaultRole().name());
        map.put("active", view.active());
        return map;
    }
}
